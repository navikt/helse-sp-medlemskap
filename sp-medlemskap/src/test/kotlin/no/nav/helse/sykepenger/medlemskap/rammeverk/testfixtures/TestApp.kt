package no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures

import io.ktor.server.application.Application
import no.nav.helse.sykepenger.medlemskap.rammeverk.openapi.OpenApiConfig
import no.nav.helse.sykepenger.medlemskap.rammeverk.openapi.configureOpenApiPlugin
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureCallId
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureCallLogging
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureContentNegotiation
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureResources
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureStatusPages

/** Setter opp appens Ktor-plugins (unntatt auth) i en test- eller lokal kontekst. */
fun Application.installTestPlugins(
    openApiConfig: OpenApiConfig = OpenApiConfig(eksponerOpenApi = true, tittel = "test"),
) {
    configureCallId()
    configureCallLogging()
    configureContentNegotiation()
    configureStatusPages()
    configureResources()
    configureOpenApiPlugin(openApiConfig)
}
