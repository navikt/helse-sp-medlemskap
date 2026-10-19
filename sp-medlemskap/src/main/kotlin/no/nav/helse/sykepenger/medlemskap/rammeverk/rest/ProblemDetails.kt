package no.nav.helse.sykepenger.medlemskap.rammeverk.rest

import kotlinx.serialization.Serializable

/**
 * RFC 7807 ("Problem Details for HTTP APIs") problem+json-respons.
 *
 * `@Serializable` brukes KUN av OpenAPI-schema-generatoren (`SchemaGenerator.kotlinx`, se
 * `openapi.ConfigureOpenApiPlugin`) slik at feilresponsen blir dokumentert i spec-en — selve
 * (de)serialiseringen på treet skjer fortsatt via Jackson (`ConfigureContentNegotiation`), uendret.
 */
@Serializable
data class ProblemDetails(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String,
)
