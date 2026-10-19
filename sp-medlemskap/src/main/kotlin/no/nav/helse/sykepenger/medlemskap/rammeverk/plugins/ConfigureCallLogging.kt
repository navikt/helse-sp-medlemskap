package no.nav.helse.sykepenger.medlemskap.rammeverk.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.path
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val ENGANGSSTIER_UTEN_LOGGING = setOf("/metrics", "/isalive", "/isready")

fun Application.configureCallLogging() {
    install(CallLogging) {
        disableDefaultColors()
        logger = LoggerFactory.getLogger("tjenestekall")
        level = Level.INFO
        callIdMdc("callId")
        filter { call -> call.request.path() !in ENGANGSSTIER_UTEN_LOGGING }
    }
}
