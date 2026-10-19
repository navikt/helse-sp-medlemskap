package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.auth.authentication
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.medlemskap.application.PersonAvstemmingService
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.infra.db.Database
import no.nav.helse.sykepenger.medlemskap.infra.db.DatabaseTest
import no.nav.helse.sykepenger.medlemskap.infra.db.FØDSELSNUMMER
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisClientException
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.AccessToken
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.NavIdent
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Saksbehandler
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.SaksbehandlerOid
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.SaksbehandlerPrincipal
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Tilgang
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureContentNegotiation
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureResources
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.KallKontekst
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.RestAdapter
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.RestResponse
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.get
import no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures.InMemoryPersonPseudoIdProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal class GetVilkårsvurderingerForPersonTransaksjonTest : DatabaseTest() {
    private val transaksjonProvider = Database.transaksjonProvider
    private val identitetsnummer = Identitetsnummer(FØDSELSNUMMER)
    private val saksbehandler = Saksbehandler(NavIdent("Z999999"), SaksbehandlerOid("oid"), "Test Testesen")
    private val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
    private val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

    private val tillatAlt =
        object : PopulasjonstilgangskontrollProvider {
            override fun kontrollerKomplettTilgang(
                accessToken: String,
                fødselsnummer: String,
            ) = TilgangskontrollResultat.Ok

            override fun kontrollerKjerneTilgang(
                accessToken: String,
                fødselsnummer: String,
            ) = TilgangskontrollResultat.Ok

            override fun kontrollerKjerneTilgangForAnsatt(
                ansattId: String,
                fødselsnummer: String,
            ) = TilgangskontrollResultat.Ok
        }

    private class FakeSpleisClient(
        private val vurderinger: List<SpleisMedlemskapsvurdering> = emptyList(),
        private val svikt: RuntimeException? = null,
    ) : ISpleisClient {
        override fun hentMedlemskapsvurderinger(fødselsnummer: String): List<SpleisMedlemskapsvurdering> {
            svikt?.let { throw it }
            return vurderinger
        }
    }

    private fun behandler(spleisClient: ISpleisClient) = GetVilkårsvurderingerForPersonBehandler(PersonAvstemmingService(transaksjonProvider, spleisClient))

    private fun Application.settOppTestapp(spleisClient: ISpleisClient) {
        configureContentNegotiation()
        configureResources()
        intercept(ApplicationCallPipeline.Plugins) {
            call.authentication.principal(
                SaksbehandlerPrincipal(saksbehandler, setOf(Tilgang.Les), AccessToken("token")),
            )
        }
        val restAdapter =
            RestAdapter<Transaksjonskontekst>(
                personPseudoIdProvider = pseudoIdProvider,
                populasjonstilgangskontrollProvider = tillatAlt,
                transaksjonProvider = transaksjonProvider,
            )
        routing { get(behandler(spleisClient), restAdapter) }
    }

    private fun kallKontekst(transaksjon: Transaksjonskontekst) =
        KallKontekst<Transaksjonskontekst>(
            saksbehandler = saksbehandler,
            tilganger = setOf(Tilgang.Les),
            transaksjon = transaksjon,
            accessToken = AccessToken("token"),
            personPseudoIdProvider = pseudoIdProvider,
            populasjonstilgangskontrollProvider = tillatAlt,
        )

    private fun resource(id: MedlemskapsvurderingId) = ApiVilkårsvurderingerForPersonResource(pseudoId.toString(), id.value)

    @Test
    fun `kallets transaksjon ser vurderingene som avstemmingen har commitet i egen transaksjon`() =
        testApplication {
            val etterspurt = spleisSelvstendig(skjæringstidspunkt = LocalDate.of(2024, 3, 1))
            val enAnnen = spleisSelvstendig(skjæringstidspunkt = LocalDate.of(2024, 5, 1))
            application { settOppTestapp(FakeSpleisClient(listOf(enAnnen, etterspurt))) }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${etterspurt.medlemskapsvurderingId.value}")

            assertEquals(HttpStatusCode.OK, response.status) {
                "Kallets transaksjon må se det avstemmingen har commitet på en annen forbindelse"
            }
            val json = jacksonObjectMapper().readTree(response.bodyAsText())
            assertEquals("2024-03-01", json["skjæringstidspunkt"].asString())
            val krav = json["krav"].single()
            assertEquals(etterspurt.medlemskapsvurderingId.value.toString(), krav["id"].asString()) {
                "Responsen skal inneholde den etterspurte vurderingen, ikke en annen av personens vurderinger: $json"
            }
            assertEquals("OVERFORT_FRA_SPLEIS", krav["kravkilde"].asString())
            assertEquals(2, Database.antallRader("medlemskapsvurdering"))
            assertEquals(1, Database.antallRader("migreringslogg"))
            val lagret = transaksjon { it.medlemskapsvurderinger.finn(enAnnen.medlemskapsvurderingId) }
            assertEquals(FØDSELSNUMMER, lagret?.fødselsnummer)
        }

    @Test
    fun `avstemmingen er commitet før kallets transaksjon er ferdig`() {
        val spleisVurdering = spleisSelvstendig()

        val svar =
            transaksjonProvider.transaksjon { kallTransaksjon ->
                val svar = behandler(FakeSpleisClient(listOf(spleisVurdering))).behandle(resource(spleisVurdering.medlemskapsvurderingId), kallKontekst(kallTransaksjon))

                // Kallets transaksjon er fortsatt åpen, men en annen forbindelse ser allerede dataene.
                assertEquals(1, Database.antallRader("medlemskapsvurdering"))
                assertEquals(1, Database.antallRader("migreringslogg"))
                svar
            }

        assertInstanceOf(RestResponse.Ok::class.java, svar)
    }

    @Test
    fun `avstemmingen blir liggende selv om kallets transaksjon rulles tilbake`() {
        val spleisVurdering = spleisSelvstendig()

        assertThrows<IllegalStateException> {
            transaksjonProvider.transaksjon { kallTransaksjon ->
                behandler(FakeSpleisClient(listOf(spleisVurdering))).behandle(resource(spleisVurdering.medlemskapsvurderingId), kallKontekst(kallTransaksjon))
                error("noe feiler etter avstemmingen, f.eks. under serialisering")
            }
        }

        assertEquals(1, Database.antallRader("medlemskapsvurdering"))
        assertEquals(1, Database.antallRader("migreringslogg"))
    }

    @Test
    fun `nytt kall etter tilbakerullet kall lagrer ikke vurderingene paa nytt`() =
        testApplication {
            val spleisVurdering = spleisSelvstendig()
            assertThrows<IllegalStateException> {
                transaksjonProvider.transaksjon { kallTransaksjon ->
                    behandler(FakeSpleisClient(listOf(spleisVurdering))).behandle(resource(spleisVurdering.medlemskapsvurderingId), kallKontekst(kallTransaksjon))
                    error("første kall feiler")
                }
            }
            application { settOppTestapp(FakeSpleisClient(svikt = IllegalStateException("Spleis skal ikke kalles igjen"))) }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${spleisVurdering.medlemskapsvurderingId.value}")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, Database.antallRader("medlemskapsvurdering"))
            assertEquals(1, Database.antallRader("migreringslogg"))
        }

    @Test
    fun `spleis-feil lagrer ingenting, slik at neste kall proever igjen`() =
        testApplication {
            application { settOppTestapp(FakeSpleisClient(svikt = SpleisClientException("spleis-api svarte 500"))) }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${UUID.randomUUID()}")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals(0, Database.antallRader("medlemskapsvurdering"))
            assertEquals(0, Database.antallRader("migreringslogg"))
        }

    @Test
    fun `konflikt paa en vurdering ruller tilbake hele avstemmingen`() {
        val gyldig = spleisSelvstendig()
        val konflikt = spleisSelvstendig()
        transaksjon {
            it.medlemskapsvurderinger.lagre(
                Medlemskapsvurdering.fraInfotrygd(
                    id = konflikt.medlemskapsvurderingId,
                    vurdertTidspunkt = konflikt.opprettet,
                    fødselsnummer = "12029240046",
                    skjæringstidspunkt = konflikt.skjæringstidspunkt,
                    erOk = true,
                ),
            )
        }

        assertThrows<IllegalStateException> {
            transaksjonProvider.transaksjon { kallTransaksjon ->
                behandler(FakeSpleisClient(listOf(gyldig, konflikt))).behandle(resource(gyldig.medlemskapsvurderingId), kallKontekst(kallTransaksjon))
            }
        }

        assertEquals(1, Database.antallRader("medlemskapsvurdering")) { "Bare den andre personens vurdering skal finnes" }
        assertEquals(0, Database.antallRader("migreringslogg"))
    }

    private fun spleisSelvstendig(skjæringstidspunkt: LocalDate = LocalDate.of(2024, 3, 1)) =
        SpleisMedlemskapsvurdering.SpleisSelvstendig(
            medlemskapsvurderingId = MedlemskapsvurderingId(UUID.randomUUID()),
            opprettet = Instant.now(),
            skjæringstidspunkt = skjæringstidspunkt,
        )
}
