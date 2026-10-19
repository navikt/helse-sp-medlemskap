package no.nav.helse.sykepenger.medlemskap.rammeverk.rest

import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.AZURE_AD_AUTHENTICATION_NAME

/**
 * Registrerer appens endepunkter bak Azure AD-autentisering. Alle ruter som settes opp via
 * [RestRuting] MÅ ligge inni `authenticate`-blokken — uten den blir ingen principal satt på kallet,
 * og [RestAdapter] svarer 401 «Uautentisert» selv for kall med gyldig token.
 */
fun <TRANSAKSJON> Application.configureRestRuting(
    restAdapter: RestAdapter<TRANSAKSJON>,
    endepunkter: RestRuting<TRANSAKSJON>.() -> Unit,
) {
    routing {
        authenticate(AZURE_AD_AUTHENTICATION_NAME) {
            RestRuting(this, restAdapter).endepunkter()
        }
    }
}

class RestRuting<TRANSAKSJON> internal constructor(
    @PublishedApi internal val route: Route,
    @PublishedApi internal val restAdapter: RestAdapter<TRANSAKSJON>,
) {
    inline fun <reified RESOURCE : Any, reified RESPONSE, ERROR : ApiErrorCode> get(
        behandler: GetBehandler<RESOURCE, RESPONSE, ERROR, TRANSAKSJON>,
    ) = with(route) { get(behandler, restAdapter) }

    inline fun <reified RESOURCE : Any, reified REQUEST : Any, reified RESPONSE, ERROR : ApiErrorCode> post(
        behandler: PostBehandler<RESOURCE, REQUEST, RESPONSE, ERROR, TRANSAKSJON>,
    ) = with(route) { post(behandler, restAdapter) }
}
