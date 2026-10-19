package no.nav.helse.sykepenger.medlemskap.rammeverk.rest

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangSomMangler
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.AccessToken
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.NavIdent
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Saksbehandler
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.SaksbehandlerOid
import no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures.InMemoryPersonPseudoIdProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.UUID

class KallKontekstTest {
    private val identitetsnummer = Identitetsnummer("12345678901")
    private val saksbehandler = Saksbehandler(NavIdent("Z999999"), SaksbehandlerOid("oid"), "Test Testesen")

    private enum class TestFeil(
        override val httpStatus: Int,
        override val tittel: String,
    ) : ApiErrorCode {
        PersonIkkeFunnet(404, "Person ikke funnet"),
        ManglerTilgang(403, "Mangler tilgang"),
    }

    private class FakeTilgangskontroll(
        private val resultat: TilgangskontrollResultat,
    ) : PopulasjonstilgangskontrollProvider {
        var antallKall = 0

        override fun kontrollerKomplettTilgang(
            accessToken: String,
            fødselsnummer: String,
        ): TilgangskontrollResultat = resultat

        override fun kontrollerKjerneTilgang(
            accessToken: String,
            fødselsnummer: String,
        ): TilgangskontrollResultat {
            antallKall++
            return resultat
        }

        override fun kontrollerKjerneTilgangForAnsatt(
            ansattId: String,
            fødselsnummer: String,
        ): TilgangskontrollResultat = resultat
    }

    private class Oppsett(
        tilgangsresultat: TilgangskontrollResultat,
        saksbehandler: Saksbehandler,
        identitetsnummer: Identitetsnummer,
    ) {
        private val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
        val personPseudoId: PersonPseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)
        val fake = FakeTilgangskontroll(tilgangsresultat)
        val kallKontekst =
            KallKontekst<Unit>(
                saksbehandler = saksbehandler,
                tilganger = emptySet(),
                transaksjon = Unit,
                accessToken = AccessToken("token"),
                personPseudoIdProvider = pseudoIdProvider,
                populasjonstilgangskontrollProvider = fake,
            )
    }

    @Test
    fun `pseudo-id ikke funnet gir personIkkeFunnet-feil, ikke exception`() {
        val oppsett = Oppsett(TilgangskontrollResultat.Ok, saksbehandler, identitetsnummer)
        val ukjentPseudoId = PersonPseudoId(UUID.randomUUID())

        val response =
            oppsett.kallKontekst.medPerson<String, TestFeil>(
                personPseudoId = ukjentPseudoId,
                personIkkeFunnet = { TestFeil.PersonIkkeFunnet },
                manglerTilgang = { TestFeil.ManglerTilgang },
            ) { RestResponse.ok("skal ikke naas") }

        assertEquals(RestResponse.feil(TestFeil.PersonIkkeFunnet), response)
    }

    @Test
    fun `manglende populasjonstilgang gir manglerTilgang-feil`() {
        val oppsett =
            Oppsett(TilgangskontrollResultat.ManglerTilgang(TilgangSomMangler.Habilitet), saksbehandler, identitetsnummer)

        val response =
            oppsett.kallKontekst.medPerson<String, TestFeil>(
                personPseudoId = oppsett.personPseudoId,
                personIkkeFunnet = { TestFeil.PersonIkkeFunnet },
                manglerTilgang = { TestFeil.ManglerTilgang },
            ) { RestResponse.ok("skal ikke naas") }

        assertEquals(RestResponse.feil(TestFeil.ManglerTilgang), response)
        assertEquals(1, oppsett.fake.antallKall)
    }

    @Test
    fun `ok tilgang kjoerer block med identitetsnummer`() {
        val oppsett = Oppsett(TilgangskontrollResultat.Ok, saksbehandler, identitetsnummer)
        var mottattIdentitetsnummer: Identitetsnummer? = null

        val response =
            oppsett.kallKontekst.medPerson<String, TestFeil>(
                personPseudoId = oppsett.personPseudoId,
                personIkkeFunnet = { TestFeil.PersonIkkeFunnet },
                manglerTilgang = { TestFeil.ManglerTilgang },
            ) { ident ->
                mottattIdentitetsnummer = ident
                RestResponse.ok("ok")
            }

        assertEquals(RestResponse.ok("ok"), response)
        assertEquals(identitetsnummer, mottattIdentitetsnummer)
    }

    @Test
    fun `tilgangskontroll skjer foer block kalles, aldri etter`() {
        val oppsett =
            Oppsett(TilgangskontrollResultat.ManglerTilgang(TilgangSomMangler.EgenAnsatt), saksbehandler, identitetsnummer)
        var blockKalt = false

        oppsett.kallKontekst.medPerson<String, TestFeil>(
            personPseudoId = oppsett.personPseudoId,
            personIkkeFunnet = { TestFeil.PersonIkkeFunnet },
            manglerTilgang = { TestFeil.ManglerTilgang },
        ) {
            blockKalt = true
            RestResponse.ok("skal ikke naas")
        }

        assertFalse(blockKalt, "block skal ikke kalles når tilgangskontrollen avslår")
        assertEquals(1, oppsett.fake.antallKall)
    }
}
