package no.nav.helse.sykepenger.medlemskap.rammeverk.rest

import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.AzureAdConfig
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Tilgang
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.TilgangsgrupperTilTilganger
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.configureJwtAuthentication
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureContentNegotiation
import no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures.InMemoryPersonPseudoIdProvider
import no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures.TokenUtsteder
import no.nav.security.mock.oauth2.MockOAuth2Server
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private enum class RutingTestFeil(
    override val httpStatus: Int,
    override val tittel: String,
) : ApiErrorCode

private object RutingResource

private object RutingBehandler : GetBehandler<RutingResource, String, RutingTestFeil, Unit> {
    override val påkrevdTilgang = Tilgang.Les
    override val tag = "test"

    override fun behandle(
        resource: RutingResource,
        kallKontekst: KallKontekst<Unit>,
    ): RestResponse<String, RutingTestFeil> = RestResponse.ok("hei ${kallKontekst.saksbehandler.navIdent.value}")
}

private object ÅpenTilgangskontroll : PopulasjonstilgangskontrollProvider {
    override fun kontrollerKomplettTilgang(
        accessToken: String,
        fødselsnummer: String,
    ): TilgangskontrollResultat = TilgangskontrollResultat.Ok

    override fun kontrollerKjerneTilgang(
        accessToken: String,
        fødselsnummer: String,
    ): TilgangskontrollResultat = TilgangskontrollResultat.Ok

    override fun kontrollerKjerneTilgangForAnsatt(
        ansattId: String,
        fødselsnummer: String,
    ): TilgangskontrollResultat = TilgangskontrollResultat.Ok
}

/**
 * Regresjonstest: endepunktene som registreres via [configureRestRuting] må ligge bak
 * `authenticate`. Uten det blir ingen principal satt, og [RestAdapter] svarer 401 «Uautentisert»
 * selv for kall med gyldig Azure AD-token.
 */
class ConfigureRestRutingTest {
    private val mockOAuth2Server = MockOAuth2Server()

    @BeforeEach
    fun start() = mockOAuth2Server.start()

    @AfterEach
    fun stop() = mockOAuth2Server.shutdown()

    private fun Application.settOppApp() {
        configureContentNegotiation()
        configureJwtAuthentication(
            azureAdConfig =
                AzureAdConfig(
                    clientId = "test-client-id",
                    issuerUrl = mockOAuth2Server.issuerUrl("azuread").toString(),
                    jwkProviderUri = mockOAuth2Server.jwksUrl("azuread").toString(),
                ),
            tilgangsgrupperTilTilganger = TilgangsgrupperTilTilganger(tilgangLesGruppeIder = setOf("les-uuid"), tilgangSkrivGruppeIder = setOf("skriv-uuid")),
        )
        val restAdapter =
            RestAdapter<Unit>(
                personPseudoIdProvider = InMemoryPersonPseudoIdProvider(),
                populasjonstilgangskontrollProvider = ÅpenTilgangskontroll,
                transaksjonProvider =
                    object : TransaksjonProvider<Unit> {
                        override fun <T> transaksjon(block: (Unit) -> T): T = block(Unit)
                    },
            )
        // Bruker en vanlig ktor-rute framfor `Resources`-DSL-en i `Ruting.kt` (som forutsetter
        // kotlinx.serialization-kompilatorpluginet). Poenget her er at ruta registreres via samme
        // `Route`-mottaker som `RestRuting` bruker, altså inni `authenticate`-blokken.
        configureRestRuting(restAdapter, endepunkter = {
            route.get("/enkel") {
                restAdapter.håndter(call, RutingResource, RutingBehandler) { r, kk -> RutingBehandler.behandle(r, kk) }
            }
        })
    }

    @Test
    fun `gyldig token gir 200 - endepunktene ligger bak authenticate slik at principal blir satt`() =
        testApplication {
            application { settOppApp() }

            val token =
                TokenUtsteder(mockOAuth2Server, issuerId = "azuread", audience = "test-client-id")
                    .utstedSaksbehandlerToken(navIdent = "Z999999", entraGrupper = setOf("les-uuid"))

            val response = client.get("/enkel") { header("Authorization", "Bearer $token") }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("hei Z999999", response.bodyAsText())
        }

    @Test
    fun `kall uten token gir 401`() =
        testApplication {
            application { settOppApp() }

            val response = client.get("/enkel")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `saksbehandler kun i skrivegruppa naar Les-endepunkt - skriv impliserer les`() =
        testApplication {
            application { settOppApp() }

            val token =
                TokenUtsteder(mockOAuth2Server, issuerId = "azuread", audience = "test-client-id")
                    .utstedSaksbehandlerToken(navIdent = "Z999999", entraGrupper = setOf("skriv-uuid"))

            val response = client.get("/enkel") { header("Authorization", "Bearer $token") }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `saksbehandler uten tilgangsgrupper gir 403`() =
        testApplication {
            application { settOppApp() }

            val token =
                TokenUtsteder(mockOAuth2Server, issuerId = "azuread", audience = "test-client-id")
                    .utstedSaksbehandlerToken(navIdent = "Z999999", entraGrupper = emptySet())

            val response = client.get("/enkel") { header("Authorization", "Bearer $token") }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
}
