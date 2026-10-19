package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.medlemskap.application.InMemoryTransaksjonProvider
import no.nav.helse.sykepenger.medlemskap.application.PersonAvstemmingService
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.rammeverk.openapi.OpenApiConfig
import no.nav.helse.sykepenger.medlemskap.rammeverk.openapi.configureOpenApiPlugin
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureContentNegotiation
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureResources
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.RestAdapter
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.get
import no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures.InMemoryPersonPseudoIdProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class VilkårsvurderingerForPersonOpenApiTest {
    private fun Application.settOppTestapp() {
        val transaksjonProvider = InMemoryTransaksjonProvider()
        configureContentNegotiation()
        configureResources()
        val restAdapter =
            RestAdapter<Transaksjonskontekst>(
                personPseudoIdProvider = InMemoryPersonPseudoIdProvider(),
                populasjonstilgangskontrollProvider =
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
                    },
                transaksjonProvider = transaksjonProvider,
            )
        configureOpenApiPlugin(OpenApiConfig(eksponerOpenApi = true, tittel = "sp-medlemskap"))
        val spleisService =
            PersonAvstemmingService(
                transaksjonProvider,
                object : ISpleisClient {
                    override fun hentMedlemskapsvurderinger(fødselsnummer: String): List<SpleisMedlemskapsvurdering> = emptyList()
                },
            )
        routing {
            get(GetVilkårsvurderingerForPersonBehandler(spleisService), restAdapter)
            get(GetSaksbehandlerhandlingVisningBehandler(), restAdapter)
        }
    }

    @Test
    fun `openapi-specen dokumenterer de nye query-parametrene som typet uuid`() =
        testApplication {
            application { settOppTestapp() }
            startApplication()

            val response = client.get("/api/openapi.json")

            assertEquals(HttpStatusCode.OK, response.status)
            val spec = response.bodyAsText()

            assertTrue(spec.contains("/api/personer/{personId}/vilkarsvurderinger")) { "Forventet at ruten var dokumentert: $spec" }
            assertTrue(spec.contains("medlemskapsvurderingId")) { "Forventet query-parameteret medlemskapsvurderingId i spec-en: $spec" }
            assertTrue(spec.contains("\"format\" : \"uuid\"")) { "Forventet at UUID-parametrene var typet med format uuid: $spec" }
            assertTrue(spec.contains("/api/personer/{personId}/saksbehandlerhandlinger/{handlingId}/visning")) {
                "Forventet at visningsruta var dokumentert: $spec"
            }
        }

    /**
     * Kildevariantene er unioner, og en union uten diskriminator er ubrukelig for en
     * konsument som genererer typer fra spec-en: variantene ville ikke vært til å skille fra
     * hverandre.
     *
     * Diskriminatoren settes av Jackson, mens spec-en genereres fra kotlinx-annotasjonene. De to kan
     * altså komme i utakt uten at noe annet ryker, og denne testen er det eneste som fanger det.
     */
    @Test
    fun `openapi-specen dokumenterer diskriminatoren paa alle unionsvarianter`() =
        testApplication {
            application { settOppTestapp() }
            startApplication()

            val schemas =
                jacksonObjectMapper()
                    .readTree(client.get("/api/openapi.json").bodyAsText())["components"]["schemas"]

            listOf(
                "ApiVurderingskilde.Automatisk" to "kildetype",
                "ApiVurderingskilde.Saksbehandler" to "kildetype",
                "ApiVurderingskilde.OverførtFraSpleis" to "kildetype",
                "ApiVurderingskilde.OverførtFraInfotrygd" to "kildetype",
                "ApiVisningsfelt.Tekst" to "type",
                "ApiVisningsfelt.Liste" to "type",
            ).forEach { (skjema, diskriminator) ->
                assertTrue(schemas[skjema]?.get("properties")?.has(diskriminator) == true) {
                    "Forventet diskriminatoren $diskriminator i skjemaet $skjema: ${schemas[skjema]}"
                }
            }
        }
}
