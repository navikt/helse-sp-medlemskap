package no.nav.helse.sykepenger.medlemskap.rammeverk.auth

import com.auth0.jwk.JwkProviderBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import java.net.URI
import java.util.concurrent.TimeUnit

const val AZURE_AD_AUTHENTICATION_NAME = "azure-ad-jwt"

fun Application.configureJwtAuthentication(
    azureAdConfig: AzureAdConfig,
    tilgangsgrupperTilTilganger: TilgangsgrupperTilTilganger,
) {
    val jwkProvider =
        JwkProviderBuilder(URI(azureAdConfig.jwkProviderUri).toURL())
            .cached(10, 24, TimeUnit.HOURS)
            .rateLimited(10, 1, TimeUnit.MINUTES)
            .build()

    install(Authentication) {
        jwt(AZURE_AD_AUTHENTICATION_NAME) {
            verifier(jwkProvider, azureAdConfig.issuerUrl) {
                withAudience(azureAdConfig.clientId)
            }
            validate { credential ->
                val payload = credential.payload

                if (payload.issuer != azureAdConfig.issuerUrl) return@validate null
                if (azureAdConfig.clientId !in payload.audience) return@validate null

                val navIdent = payload.getClaim("NAVident").asString() ?: return@validate null
                val oid = payload.getClaim("oid").asString() ?: return@validate null
                val navn =
                    payload.getClaim("name").asString()
                        ?: payload.getClaim("preferred_username").asString()
                        ?: navIdent

                val entraGrupper =
                    runCatching { payload.getClaim("groups").asList(String::class.java) }
                        .getOrNull()
                        ?.toSet()
                        ?: emptySet()

                val saksbehandler = Saksbehandler(NavIdent(navIdent), SaksbehandlerOid(oid), navn)
                val tilganger = tilgangsgrupperTilTilganger.tilganger(entraGrupper)

                // Rå Authorization-header, IKKE noe som logges — kun videreført til AccessToken for
                // senere OBO-bruk (Texas).
                val accessToken =
                    request.headers[HttpHeaders.Authorization]
                        ?.removePrefix("Bearer ")
                        ?.let(::AccessToken)
                        ?: return@validate null

                SaksbehandlerPrincipal(saksbehandler, tilganger, accessToken)
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized)
            }
        }
    }
}
