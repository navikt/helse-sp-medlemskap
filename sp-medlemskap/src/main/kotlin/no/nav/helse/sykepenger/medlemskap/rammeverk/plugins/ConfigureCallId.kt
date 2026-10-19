package no.nav.helse.sykepenger.medlemskap.rammeverk.plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import java.util.UUID

fun Application.configureCallId() {
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { callId -> callId.isNotEmpty() }
    }
}
