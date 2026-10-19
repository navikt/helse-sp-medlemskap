package no.nav.helse.sykepenger.medlemskap.infra.db

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.LocalDate
import java.util.UUID

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = VurderingskildeDto.Automatisk::class, name = "AUTOMATISK"),
    JsonSubTypes.Type(value = VurderingskildeDto.Saksbehandler::class, name = "SAKSBEHANDLER"),
    JsonSubTypes.Type(value = VurderingskildeDto.OverførtFraSpleis::class, name = "OVERFOERT_FRA_SPLEIS"),
    JsonSubTypes.Type(value = VurderingskildeDto.OverførtFraInfotrygd::class, name = "OVERFOERT_FRA_INFOTRYGD"),
)
internal sealed interface VurderingskildeDto {
    data class Automatisk(
        val prøvingId: UUID,
        val oppslagFom: LocalDate,
        val oppslagTom: LocalDate,
        val versjonAvKildekode: String,
    ) : VurderingskildeDto

    data class Saksbehandler(
        val ident: String,
        val fritekstbegrunnelse: String,
        val journalpostId: List<String> = emptyList(),
    ) : VurderingskildeDto

    class OverførtFraSpleis : VurderingskildeDto

    class OverførtFraInfotrygd : VurderingskildeDto
}
