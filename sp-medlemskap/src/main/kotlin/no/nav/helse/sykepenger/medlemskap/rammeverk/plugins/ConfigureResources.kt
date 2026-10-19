package no.nav.helse.sykepenger.medlemskap.rammeverk.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.resources.Resources
import no.nav.helse.sykepenger.medlemskap.rammeverk.serialization.customSerializersModule

fun Application.configureResources() {
    install(Resources) {
        serializersModule = customSerializersModule
    }
}
