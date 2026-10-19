package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangSomMangler
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import no.nav.helse.sykepenger.medlemskap.application.InMemoryTransaksjonProvider
import no.nav.helse.sykepenger.medlemskap.domain.*
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.*
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureContentNegotiation
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureResources
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.RestAdapter
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.get
import no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures.InMemoryPersonPseudoIdProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.*

class GetSaksbehandlerhandlingVisningBehandlerTest {
    private val saksbehandler = Saksbehandler(NavIdent("Z999999"), SaksbehandlerOid("oid"), "Test Testesen")
    private val identitetsnummer = Identitetsnummer("12345678901")
    private val skjæringstidspunkt = LocalDate.of(2024, 1, 1)

    @Test
    fun `viser alle fire feltene i riktig rekkefoelge`() =
        testApplication {
            val vurdering =
                manuellVurdering(
                    fritekstbegrunnelse = "Fritekst fra saksbehandler\nmed linjeskift",
                    journalpostId = listOf("598373838", "598373839"),
                )
            val pseudoId = settOpp(lagret = listOf(vurdering))

            val response = client.get(url(pseudoId, vurdering.id.value))

            assertEquals(HttpStatusCode.OK, response.status)
            val felter = response.json()["felter"]
            assertEquals(4, felter.size())
            assertTekst(felter[0], "Vilkår", "Arbeid i minst 4 uker")
            assertTekst(felter[1], "Utfall", "Oppfylt")
            assertTekst(felter[2], "Begrunnelse", "Fritekst fra saksbehandler\nmed linjeskift")
            assertEquals("LISTE", felter[3]["type"].asString())
            assertEquals("Journalposter", felter[3]["tittel"].asString())
            assertEquals(listOf("598373838", "598373839"), felter[3]["elementer"].values().map { it.asString() })
        }

    @Test
    fun `journalposter utelates naar lista er tom`() =
        testApplication {
            val vurdering = manuellVurdering(journalpostId = emptyList())
            val pseudoId = settOpp(lagret = listOf(vurdering))

            val response = client.get(url(pseudoId, vurdering.id.value))

            assertEquals(HttpStatusCode.OK, response.status)
            val felter = response.json()["felter"]
            assertEquals(listOf("Vilkår", "Utfall", "Begrunnelse"), felter.values().map { it["tittel"].asString() })
        }

    @Test
    fun `ikke oppfylt vises som Ikke oppfylt`() =
        testApplication {
            val vurdering = manuellVurdering(utfall = Utfall.IkkeOppfylt)
            val pseudoId = settOpp(lagret = listOf(vurdering))

            val response = client.get(url(pseudoId, vurdering.id.value))

            assertTekst(response.json()["felter"][1], "Utfall", "Ikke oppfylt")
        }

    @Test
    fun `viser nyeste manuelle vurdering naar flere er med`() =
        testApplication {
            val eldre = Vilkårsvurdering.avSaksbehandler(
                Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                Utfall.IkkeOppfylt,
                "Z1",
                "eldre"
            )
            val nyere =
                Vilkårsvurdering
                    .avSaksbehandler(Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER, Utfall.Oppfylt, "Z1", "nyere")
                    .copy(vurdertTidspunkt = eldre.vurdertTidspunkt.plusSeconds(60))
            val vurdering =
                Medlemskapsvurdering.VurdertISpVilkårsprøving.med(
                    fødselsnummer = identitetsnummer.value,
                    skjæringstidspunkt = skjæringstidspunkt,
                    kategori = Kategori.Arbeidstaker,
                    vilkårsvurderinger = listOf(nyere, eldre),
                )
            val pseudoId = settOpp(lagret = listOf(vurdering))

            val felter = client.get(url(pseudoId, vurdering.id.value)).json()["felter"]

            assertTekst(felter[1], "Utfall", "Oppfylt")
            assertTekst(felter[2], "Begrunnelse", "nyere")
        }

    @Test
    fun `ukjent id gir 404`() =
        testApplication {
            val pseudoId = settOpp()

            val response = client.get(url(pseudoId, UUID.randomUUID()))

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("Fant ikke detaljer", response.json()["title"].asString())
        }

    @Test
    fun `id som tilhoerer en annen person gir samme 404 som ukjent id`() =
        testApplication {
            val andresVurdering = manuellVurdering(fødselsnummer = "98765432109")
            val pseudoId = settOpp(lagret = listOf(andresVurdering))

            val annenPerson = client.get(url(pseudoId, andresVurdering.id.value))
            val ukjent = client.get(url(pseudoId, UUID.randomUUID()))

            assertEquals(HttpStatusCode.NotFound, annenPerson.status)
            assertEquals(ukjent.status, annenPerson.status)
            assertEquals(ukjent.json().utenInstance(), annenPerson.json().utenInstance())
        }

    @Test
    fun `bare automatisk vurdering gir 404`() =
        testApplication {
            val vurdering =
                Medlemskapsvurdering.automatisk(
                    medlemskapsprøvingId = MedlemskapsprøvingId.ny(),
                    fødselsnummer = identitetsnummer.value,
                    skjæringstidspunkt = skjæringstidspunkt,
                    grunnlag = Medlemskapsgrunnlag.SelvstendigNæringsdrivende,
                )
            val pseudoId = settOpp(lagret = listOf(vurdering))

            val response = client.get(url(pseudoId, vurdering.id.value))

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `vurdering overfoert fra infotrygd gir 404`() =
        testApplication {
            val vurdering =
                Medlemskapsvurdering.fraInfotrygd(
                    fødselsnummer = identitetsnummer.value,
                    skjæringstidspunkt = skjæringstidspunkt,
                    erOk = true,
                    vurdertTidspunkt = Instant.now(),
                )
            val pseudoId = settOpp(lagret = listOf(vurdering))

            val response = client.get(url(pseudoId, vurdering.id.value))

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `ugyldig handlingId gir samme 404 som ukjent id, ikke 400`() =
        testApplication {
            val pseudoId = settOpp()

            val ugyldig = client.get("/api/personer/$pseudoId/saksbehandlerhandlinger/ikke-en-uuid/visning")
            val ukjent = client.get(url(pseudoId, UUID.randomUUID()))

            assertEquals(HttpStatusCode.NotFound, ugyldig.status)
            assertEquals(ukjent.json().utenInstance(), ugyldig.json().utenInstance())
        }

    @Test
    fun `manglende tilgang til personen gir 403`() =
        testApplication {
            val vurdering = manuellVurdering()
            val pseudoId =
                settOpp(
                    lagret = listOf(vurdering),
                    tilgangskontroll = FakeTilgangskontroll(TilgangskontrollResultat.ManglerTilgang(TilgangSomMangler.Habilitet)),
                )

            val response = client.get(url(pseudoId, vurdering.id.value))

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `kall uten Les-tilgang gir 403`() =
        testApplication {
            val vurdering = manuellVurdering()
            val pseudoId = settOpp(lagret = listOf(vurdering), tilganger = emptySet())

            val response = client.get(url(pseudoId, vurdering.id.value))

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `ukjent person gir 404`() =
        testApplication {
            settOpp()

            val response = client.get(url(UUID.randomUUID().toString(), UUID.randomUUID()))

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    private class FakeTilgangskontroll(
        private val resultat: TilgangskontrollResultat = TilgangskontrollResultat.Ok,
    ) : PopulasjonstilgangskontrollProvider {
        override fun kontrollerKomplettTilgang(
            accessToken: String,
            fødselsnummer: String,
        ) = resultat

        override fun kontrollerKjerneTilgang(
            accessToken: String,
            fødselsnummer: String,
        ) = resultat

        override fun kontrollerKjerneTilgangForAnsatt(
            ansattId: String,
            fødselsnummer: String,
        ) = resultat
    }

    private fun ApplicationTestBuilder.settOpp(
        lagret: List<Medlemskapsvurdering> = emptyList(),
        tilganger: Set<Tilgang> = setOf(Tilgang.Les),
        tilgangskontroll: PopulasjonstilgangskontrollProvider = FakeTilgangskontroll(),
    ): String {
        val transaksjonProvider = InMemoryTransaksjonProvider()
        lagret.forEach { transaksjonProvider.medlemskapsvurderinger.lagre(it) }
        val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
        val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)
        application {
            settOppTestapp(
                SaksbehandlerPrincipal(saksbehandler, tilganger, AccessToken("token")),
                transaksjonProvider,
                tilgangskontroll,
                pseudoIdProvider
            )
        }
        return pseudoId.toString()
    }

    private fun Application.settOppTestapp(
        principal: SaksbehandlerPrincipal,
        transaksjonProvider: InMemoryTransaksjonProvider,
        tilgangskontroll: PopulasjonstilgangskontrollProvider,
        personPseudoIdProvider: InMemoryPersonPseudoIdProvider,
    ) {
        configureContentNegotiation()
        configureResources()
        intercept(ApplicationCallPipeline.Plugins) {
            call.authentication.principal(principal)
        }
        val restAdapter =
            RestAdapter(
                personPseudoIdProvider = personPseudoIdProvider,
                populasjonstilgangskontrollProvider = tilgangskontroll,
                transaksjonProvider = transaksjonProvider,
            )
        routing {
            get(GetSaksbehandlerhandlingVisningBehandler(), restAdapter)
        }
    }

    private fun manuellVurdering(
        fødselsnummer: String = identitetsnummer.value,
        utfall: Utfall = Utfall.Oppfylt,
        fritekstbegrunnelse: String = "Begrunnelse",
        journalpostId: List<String> = listOf("598373838"),
    ) = Medlemskapsvurdering.avSaksbehandler(
        fødselsnummer = fødselsnummer,
        skjæringstidspunkt = skjæringstidspunkt,
        vilkårsvurdering =
            Vilkårsvurdering.avSaksbehandler(
                vilkårskode = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                utfall = utfall,
                saksbehandlerIdent = "Z999999",
                fritekstbegrunnelse = fritekstbegrunnelse,
                journalpostId = journalpostId,
            ),
    )
}

private fun url(
    pseudoId: String,
    handlingId: UUID,
) = "/api/personer/$pseudoId/saksbehandlerhandlinger/$handlingId/visning"

private suspend fun HttpResponse.json(): JsonNode = jacksonObjectMapper().readTree(bodyAsText())

private fun JsonNode.utenInstance(): Map<String, String> =
    properties().filter { it.key != "instance" }.associate { it.key to it.value.toString() }

private fun assertTekst(
    felt: JsonNode,
    tittel: String,
    tekst: String,
) {
    assertEquals("TEKST", felt["type"].asString())
    assertEquals(tittel, felt["tittel"].asString())
    assertEquals(tekst, felt["tekst"].asString())
}
