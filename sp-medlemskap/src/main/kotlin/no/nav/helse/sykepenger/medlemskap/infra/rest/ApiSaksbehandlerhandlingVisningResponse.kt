package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import kotlinx.serialization.Serializable

@Serializable
internal data class ApiSaksbehandlerhandlingVisningResponse(
    val felter: List<ApiVisningsfelt>,
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = ApiVisningsfelt.Tekst::class, name = "TEKST"),
    JsonSubTypes.Type(value = ApiVisningsfelt.Liste::class, name = "LISTE"),
)
@Serializable
internal sealed interface ApiVisningsfelt {
    val tittel: String
    val type: ApiVisningsfelttype

    @Serializable
    @JsonPropertyOrder("type", "tittel", "tekst")
    data class Tekst(
        override val tittel: String,
        val tekst: String,
    ) : ApiVisningsfelt {
        override val type = ApiVisningsfelttype.TEKST
    }

    @Serializable
    @JsonPropertyOrder("type", "tittel", "elementer")
    data class Liste(
        override val tittel: String,
        val elementer: List<String>,
    ) : ApiVisningsfelt {
        override val type = ApiVisningsfelttype.LISTE
    }
}

@Serializable
internal enum class ApiVisningsfelttype {
    TEKST,
    LISTE,
}
