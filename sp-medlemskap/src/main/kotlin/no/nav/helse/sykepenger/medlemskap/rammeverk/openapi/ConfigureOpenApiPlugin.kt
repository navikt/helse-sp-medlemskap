package no.nav.helse.sykepenger.medlemskap.rammeverk.openapi

import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.AuthScheme
import io.github.smiley4.ktoropenapi.config.AuthType
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.ktoropenapi.config.SchemaOverwriteModule
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.github.smiley4.schemakenerator.swagger.data.RefType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.swagger.v3.oas.models.media.Schema
import no.nav.helse.sykepenger.medlemskap.rammeverk.serialization.customSerializersModule
import no.nav.sykepenger.libs.logging.loggInfo
import java.math.BigDecimal

fun Application.configureOpenApiPlugin(config: OpenApiConfig) {
    if (!config.eksponerOpenApi) {
        loggInfo("OpenAPI/Swagger er ikke eksponert (EKSPONER_OPENAPI=false)")
        return
    }
    install(OpenApi) {
        info {
            title = config.tittel
            version = config.versjon
        }
        pathFilter = { _, url -> url.firstOrNull() == "api" }
        autoDocumentResourcesRoutes = true
        schemas {
            generator =
                SchemaGenerator.kotlinx {
                    referencePath = RefType.OPENAPI_SIMPLE
                    serializersModule = customSerializersModule
                    overwrite(SchemaGenerator.TypeOverwrites.JavaUuid())
                    overwrite(SchemaGenerator.TypeOverwrites.Instant())
                    overwrite(SchemaGenerator.TypeOverwrites.LocalDateTime())
                    overwrite(SchemaGenerator.TypeOverwrites.LocalDate())
                    overwrite(
                        object : SchemaOverwriteModule(
                            identifier = BigDecimal::class.qualifiedName!!,
                            schema = {
                                Schema<Any>().also {
                                    it.types = setOf("string")
                                    it.format = "bigdecimal"
                                }
                            },
                        ) {},
                    )
                }
        }
        security {
            securityScheme("JWT") {
                type = AuthType.HTTP
                scheme = AuthScheme.BEARER
                bearerFormat = "JWT"
            }
            defaultSecuritySchemeNames("JWT")
        }
    }
    routing {
        route("/api") {
            route("openapi.json") {
                openApi()
            }
            route("swagger") {
                swaggerUI("/api/openapi.json")
            }
        }
    }
}
