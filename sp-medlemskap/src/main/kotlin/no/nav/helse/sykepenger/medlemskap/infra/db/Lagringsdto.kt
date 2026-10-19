package no.nav.helse.sykepenger.medlemskap.infra.db

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.LocalDate

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = MedlemskapsgrunnlagDto.Arbeidstaker::class, name = "ARBEIDSTAKER"),
    JsonSubTypes.Type(value = MedlemskapsgrunnlagDto.SelvstendigNæringsdrivende::class, name = "SELVSTENDIG_NÆRINGSDRIVENDE"),
)
internal sealed interface MedlemskapsgrunnlagDto {
    data class Arbeidstaker(
        val arbeidsforhold: List<ArbeidsforholdDto>,
    ) : MedlemskapsgrunnlagDto

    class SelvstendigNæringsdrivende : MedlemskapsgrunnlagDto
}

internal data class ArbeidsforholdDto(
    val orgnummer: String,
    val fom: LocalDate,
    val tom: LocalDate,
    val type: ArbeidsforholdtypeDto,
)

internal enum class ArbeidsforholdtypeDto {
    FORENKLET_OPPGJØRSORDNING,
    FRILANSER,
    MARITIMT,
    ORDINÆRT,
    UKJENT,
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = UtledetDto.IngenUtledning::class, name = "INGEN_UTLEDNING"),
    JsonSubTypes.Type(value = UtledetDto.Medlemskapstid::class, name = "MEDLEMSKAPSTID"),
)
internal sealed interface UtledetDto {
    class IngenUtledning : UtledetDto

    data class Medlemskapstid(
        val medlemskapsperiodeFom: LocalDate?,
        val medlemskapsperiodeTom: LocalDate?,
        val medlemskapsdager: Int,
    ) : UtledetDto
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = VurderingskildeDto.Automatisk::class, name = "AUTOMATISK"),
    JsonSubTypes.Type(value = VurderingskildeDto.Saksbehandler::class, name = "SAKSBEHANDLER"),
    JsonSubTypes.Type(value = VurderingskildeDto.OverførtFraSpleis::class, name = "OVERFOERT_FRA_SPLEIS"),
)
internal sealed interface VurderingskildeDto {
    data class Automatisk(
        val prøvingId: java.util.UUID,
        val grunnlag: MedlemskapsgrunnlagDto,
        val utledet: UtledetDto,
        val versjonAvKildekode: String,
    ) : VurderingskildeDto

    data class Saksbehandler(
        val ident: String,
        val fritekstbegrunnelse: String,
        val journalpostId: List<String> = emptyList(),
    ) : VurderingskildeDto

    data class OverførtFraSpleis(
        val grunnlag: MedlemskapsgrunnlagDto,
        val utledet: UtledetDto,
    ) : VurderingskildeDto
}

internal data class LovreferanseDto(
    val lov: String,
    val paragraf: String,
    val avsnitt: Int?,
    val setning: Int?,
    val bokstav: String?,
    val iKraftFra: LocalDate,
)
