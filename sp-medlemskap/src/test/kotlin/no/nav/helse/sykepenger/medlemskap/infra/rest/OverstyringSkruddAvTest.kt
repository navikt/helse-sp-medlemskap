package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.auth.authentication
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.infra.db.Database
import no.nav.helse.sykepenger.medlemskap.infra.db.DatabaseTest
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.AccessToken
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.NavIdent
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Saksbehandler
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.SaksbehandlerOid
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.SaksbehandlerPrincipal
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Tilgang
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureContentNegotiation
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureResources
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.RestAdapter
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.post
import no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures.InMemoryPersonPseudoIdProvider
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class OverstyringSkruddAvTest : DatabaseTest() {
    private val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
    private val pseudoId = pseudoIdProvider.nyPersonPseudoId(Identitetsnummer("12345678901"))
    private val publiserteMeldinger = mutableListOf<String>()

    private object TillatAlt : PopulasjonstilgangskontrollProvider {
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

    private fun testApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                configureContentNegotiation()
                configureResources()
                intercept(ApplicationCallPipeline.Plugins) {
                    call.authentication.principal(
                        SaksbehandlerPrincipal(
                            Saksbehandler(NavIdent("Z999999"), SaksbehandlerOid("oid"), "Test Testesen"),
                            setOf(Tilgang.Les, Tilgang.Skriv),
                            AccessToken("token"),
                        ),
                    )
                }
                val restAdapter =
                    RestAdapter<Transaksjonskontekst>(
                        personPseudoIdProvider = pseudoIdProvider,
                        populasjonstilgangskontrollProvider = TillatAlt,
                        transaksjonProvider = Database.transaksjonProvider,
                    )
                routing {
                    post(PostManuellVilkårsvurderingBehandler(overstyringErSkruddPå = false), restAdapter)
                    post(
                        PostNyAutomatiskVilkårsvurderingBehandler(
                            publiser = { _, melding -> publiserteMeldinger.add(melding) },
                            overstyringErSkruddPå = false,
                        ),
                        restAdapter,
                    )
                }
            }
            block()
        }

    @Test
    fun `manuell vurdering avvises og lagrer ingenting`() =
        testApp {
            @Language("JSON")
            val request = """
                {
                  "skjæringstidspunkt": "2024-02-01",
                  "status": "JA",
                  "fritekstbegrunnelse": "Begrunnelse",
                  "journalpostId": []
                }
                """
            val response =
                client.post("/api/personer/$pseudoId/vilkarsvurderinger/manuell") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(0, Database.antallRader("medlemskapsvurdering"))
            assertEquals(0, Database.antallRader("outbox"))
        }

    @Test
    fun `ny automatisk vurdering avvises og publiserer ingenting`() =
        testApp {
            val response =
                client.post("/api/personer/$pseudoId/vilkarsvurderinger/automatisk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{ "skjæringstidspunkt": "2024-02-01" }""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(emptyList<String>(), publiserteMeldinger)
        }
}
