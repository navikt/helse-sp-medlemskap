package no.nav.helse.sykepenger.medlemskap.rammeverk.auth

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures.TokenUtsteder
import no.nav.security.mock.oauth2.MockOAuth2Server
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConfigureJwtAuthenticationTest {
    private val mockOAuth2Server = MockOAuth2Server()

    @BeforeEach
    fun start() = mockOAuth2Server.start()

    @AfterEach
    fun stop() = mockOAuth2Server.shutdown()

    private fun azureAdConfig() =
        AzureAdConfig(
            clientId = "test-client-id",
            issuerUrl = mockOAuth2Server.issuerUrl("azuread").toString(),
            jwkProviderUri = mockOAuth2Server.jwksUrl("azuread").toString(),
        )

    private fun tilgangsgrupper() = TilgangsgrupperTilTilganger(tilgangLesGruppeIder = setOf("les-uuid"), tilgangSkrivGruppeIder = setOf("skriv-uuid"))

    @Test
    fun `gyldig token med kjente grupper gir tilgang og bygger SaksbehandlerPrincipal`() =
        testApplication {
            application {
                configureJwtAuthentication(azureAdConfig(), tilgangsgrupper())
                routing {
                    authenticate(AZURE_AD_AUTHENTICATION_NAME) {
                        get("/beskyttet") {
                            val principal = call.principal<SaksbehandlerPrincipal>()!!
                            call.respondText(
                                "${principal.saksbehandler.navIdent.value}|${principal.tilganger}",
                            )
                        }
                    }
                }
            }

            val token =
                TokenUtsteder(mockOAuth2Server, issuerId = "azuread", audience = "test-client-id")
                    .utstedSaksbehandlerToken(
                        navIdent = "Z999999",
                        entraGrupper = setOf("les-uuid"),
                    )

            val response =
                client.get("/beskyttet") {
                    header("Authorization", "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.startsWith("Z999999|"), "forventet NAVident i responsen: $body")
            assertTrue(body.contains("Les"), "forventet Tilgang.Les i responsen: $body")
        }

    @Test
    fun `token uten groups-claim gir tomt tilgangssett, ikke feil`() =
        testApplication {
            application {
                configureJwtAuthentication(azureAdConfig(), tilgangsgrupper())
                routing {
                    authenticate(AZURE_AD_AUTHENTICATION_NAME) {
                        get("/beskyttet") {
                            val principal = call.principal<SaksbehandlerPrincipal>()!!
                            call.respondText("${principal.tilganger}")
                        }
                    }
                }
            }

            val token =
                TokenUtsteder(mockOAuth2Server, issuerId = "azuread", audience = "test-client-id")
                    .utstedSaksbehandlerToken(navIdent = "Z999999", entraGrupper = emptySet())

            val response =
                client.get("/beskyttet") {
                    header("Authorization", "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("[]", response.bodyAsText())
        }

    @Test
    fun `manglende token gir 401`() =
        testApplication {
            application {
                configureJwtAuthentication(azureAdConfig(), tilgangsgrupper())
                routing {
                    authenticate(AZURE_AD_AUTHENTICATION_NAME) {
                        get("/beskyttet") { call.respondText("uinnom") }
                    }
                }
            }

            val response = client.get("/beskyttet")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `token med feil audience gir 401`() =
        testApplication {
            application {
                configureJwtAuthentication(azureAdConfig(), tilgangsgrupper())
                routing {
                    authenticate(AZURE_AD_AUTHENTICATION_NAME) {
                        get("/beskyttet") { call.respondText("uinnom") }
                    }
                }
            }

            val token =
                TokenUtsteder(mockOAuth2Server, issuerId = "azuread", audience = "feil-client-id")
                    .utstedSaksbehandlerToken(navIdent = "Z999999")

            val response =
                client.get("/beskyttet") {
                    header("Authorization", "Bearer $token")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}
