package no.nav.helse.sykepenger.medlemskap.rammeverk.openapi

import io.github.smiley4.ktoropenapi.resources.get
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.install
import io.ktor.server.resources.Resources
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@Resource("/api/test-endepunkt")
private class TestResource

class ConfigureOpenApiPluginTest {
    @Test
    fun `openapi-json svarer 404 naar eksponerOpenApi er false`() =
        testApplication {
            application { configureOpenApiPlugin(OpenApiConfig(eksponerOpenApi = false, tittel = "test")) }
            val response = client.get("/api/openapi.json")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `openapi-json svarer 200 og dokumenterer registrerte ruter naar eksponerOpenApi er true`() =
        testApplication {
            application {
                install(Resources)
                configureOpenApiPlugin(OpenApiConfig(eksponerOpenApi = true, tittel = "test"))
                // `autoDocumentResourcesRoutes` krever minst én dokumentert rute for at
                // spec-en i det hele tatt skal genereres (jf. smiley4-generatoren) — en tom app
                // uten ruter produserer ingen spec, altså heller ingen `/api/openapi.json`.
                routing {
                    get<TestResource>({}) { call.respondText("ok") }
                }
            }
            // Den ekte generatoren bygger spec-en når `ApplicationStarted` fyres, ikke ved
            // pluginoppsett — `startApplication()` sørger for at det har skjedd før vi spør.
            startApplication()

            val response = client.get("/api/openapi.json")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assert(body.contains("\"title\" : \"test\"")) { "Forventet tittel i spec-en, fikk: $body" }
            assert(body.contains("/api/test-endepunkt")) { "Forventet at den registrerte ruten var dokumentert, fikk: $body" }
        }

    @Test
    fun `default eksponerOpenApi er false`() {
        assertEquals(false, OpenApiConfig(tittel = "test").eksponerOpenApi)
    }

    @Test
    fun `EKSPONER_OPENAPI=true i env slaar paa eksponering`() {
        val config = OpenApiConfig.fraEnv("test-app", mapOf("EKSPONER_OPENAPI" to "true"))
        assertEquals(true, config.eksponerOpenApi)
    }
}
