package no.nav.helse.sykepenger.medlemskap.infra.spleis

import no.nav.sykepenger.libs.logging.navngittLogger
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = navngittLogger("no.nav.helse.sykepenger.medlemskap.infra.spleis.HttpClientRetry")

internal fun <T> HttpClient.sendMedRetry(
    request: HttpRequest,
    bodyHandler: HttpResponse.BodyHandler<T>,
    maksAntallForsøk: Int = 3,
    ventetidMellomForsøk: Duration = Duration.ofMillis(500),
): HttpResponse<T> {
    var forsøk = 1
    while (true) {
        try {
            return send(request, bodyHandler)
        } catch (ex: IOException) {
            if (forsøk >= maksAntallForsøk) throw ex
            logger.warn(
                "Kall mot Spleis feilet, prøver igjen",
                ex,
                "forsøk" to forsøk.toString(),
                "maksAntallForsøk" to maksAntallForsøk.toString(),
            )
            Thread.sleep(ventetidMellomForsøk.multipliedBy(forsøk.toLong()))
            forsøk++
        }
    }
}
