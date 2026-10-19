package no.nav.helse.sykepenger.medlemskap.rammeverk.plugins

import io.ktor.serialization.jackson3.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import tools.jackson.databind.introspect.DefaultAccessorNamingStrategy

fun Application.configureContentNegotiation() {
    install(ContentNegotiation) {
        jackson {
            accessorNaming(DefaultAccessorNamingStrategy.Provider().withFirstCharAcceptance(true, true))
        }
    }
}
