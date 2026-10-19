package no.nav.helse.sykepenger.medlemskap.rammeverk.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.ProblemDetails
import no.nav.sykepenger.libs.logging.loggError

/**
 * Fanger opp exceptions som ikke er håndtert av `RestAdapter` (f.eks. feil i selve ktor-pipelinen
 * eller ruter uten `RestAdapter`). Fullstendig stacktrace/feilmelding-detaljer skal ALDRI i vanlig
 * logg eller til klienten — kun til Team Logs.
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ProblemDetails(
                    title = "Ugyldig forespørsel",
                    status = HttpStatusCode.BadRequest.value,
                    detail = null,
                    instance = call.request.uri,
                ),
            )
        }
        exception<Throwable> { call, cause ->
            loggError("Uventet feil ved kall", cause, "requestUri" to call.request.uri)
            call.respond(
                HttpStatusCode.InternalServerError,
                ProblemDetails(
                    title = "Intern serverfeil",
                    status = HttpStatusCode.InternalServerError.value,
                    detail = null, // lekk aldri stacktrace/intern tilstand til klienten
                    instance = call.request.uri,
                ),
            )
        }
    }
}
