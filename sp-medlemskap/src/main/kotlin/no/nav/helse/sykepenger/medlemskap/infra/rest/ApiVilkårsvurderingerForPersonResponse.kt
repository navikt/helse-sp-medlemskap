@file:UseContextualSerialization(UUID::class, Instant::class, LocalDate::class)

package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Serializable
internal data class ApiVilkårsvurderingerForPersonResponse(
    val skjæringstidspunkt: LocalDate,
    val krav: List<ApiMedlemskapsvurdering>,
)

@Serializable
internal data class ApiMedlemskapsvurdering(
    val id: UUID,
    val kravkode: ApiKravkode,
    val medlemskapOk: Boolean,
    val status: ApiMedlemskapsstatus,
    val vurdertTidspunkt: Instant,
    val kilde: ApiVurderingskilde,
)

@Serializable
internal enum class ApiKravkode {
    MEDLEMSKAP,
}

@Serializable
internal enum class ApiMedlemskapsstatus {
    JA,
    NEI,
    UAVKLART,
    UAVKLART_MED_BRUKERSPORSMAAL,
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kildetype", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = ApiVurderingskilde.Automatisk::class, name = "AUTOMATISK"),
    JsonSubTypes.Type(value = ApiVurderingskilde.Saksbehandler::class, name = "SAKSBEHANDLER"),
    JsonSubTypes.Type(value = ApiVurderingskilde.OverførtFraSpleis::class, name = "OVERFOERT_FRA_SPLEIS"),
    JsonSubTypes.Type(value = ApiVurderingskilde.OverførtFraInfotrygd::class, name = "OVERFOERT_FRA_INFOTRYGD"),
)
@Serializable
internal sealed interface ApiVurderingskilde {
    val kildetype: ApiKildetype

    /** Vurdert ut fra svaret fra medlemskapstjenesten for [oppslagsperiode]. [årsaker] forklarer hvorfor svaret ble uavklart. */
    @Serializable
    data class Automatisk(
        val versjonAvKildekode: String,
        val oppslagsperiode: ApiPeriode,
        val årsaker: List<ApiÅrsak> = emptyList(),
    ) : ApiVurderingskilde {
        override val kildetype: ApiKildetype = ApiKildetype.AUTOMATISK
    }

    @Serializable
    data class Saksbehandler(
        val ident: String,
        val fritekstbegrunnelse: String,
        val journalpostId: List<String> = emptyList(),
    ) : ApiVurderingskilde {
        override val kildetype: ApiKildetype = ApiKildetype.SAKSBEHANDLER
    }

    @Serializable
    data class OverførtFraSpleis(
        override val kildetype: ApiKildetype = ApiKildetype.OVERFOERT_FRA_SPLEIS,
    ) : ApiVurderingskilde {
        init {
            require(kildetype == ApiKildetype.OVERFOERT_FRA_SPLEIS) { "Diskriminatoren må stemme med varianten" }
        }
    }

    @Serializable
    data class OverførtFraInfotrygd(
        override val kildetype: ApiKildetype = ApiKildetype.OVERFOERT_FRA_INFOTRYGD,
    ) : ApiVurderingskilde {
        init {
            require(kildetype == ApiKildetype.OVERFOERT_FRA_INFOTRYGD) { "Diskriminatoren må stemme med varianten" }
        }
    }
}

@Serializable
internal enum class ApiKildetype {
    AUTOMATISK,
    SAKSBEHANDLER,
    OVERFOERT_FRA_SPLEIS,
    OVERFOERT_FRA_INFOTRYGD,
}

@Serializable
internal data class ApiPeriode(
    val fom: LocalDate,
    val tom: LocalDate,
)

@Serializable
internal data class ApiÅrsak(
    val regelId: String,
    val forklaringPaaUavklart: String,
)
