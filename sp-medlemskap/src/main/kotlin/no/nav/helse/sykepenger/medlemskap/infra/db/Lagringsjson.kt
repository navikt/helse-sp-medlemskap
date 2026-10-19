package no.nav.helse.sykepenger.medlemskap.infra.db

import no.nav.helse.Periode
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidsforhold
import no.nav.helse.sykepenger.medlemskap.domain.Lovreferanse
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsgrunnlag
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.domain.UtledetFakta
import no.nav.helse.sykepenger.medlemskap.domain.Vurderingskilde
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

private val objectMapper = jacksonObjectMapper()

/**
 * Kilden — inkludert grunnlaget og det som ble utledet, for vurderinger som har det — lagres som json i
 * én kolonne. Selve json-en er selvbeskrivende: hvilken dto den skal leses som følger av
 * `type`-diskriminatorene.
 */
internal object Vurderingskildejson {
    fun tilJson(kilde: Vurderingskilde): String = objectMapper.writeValueAsString(kilde.tilDto())

    fun fraJson(json: String): Vurderingskilde = objectMapper.readValue<VurderingskildeDto>(json).tilVurderingskilde()
}

internal object Lovreferansejson {
    fun tilJson(lovreferanse: Lovreferanse): String = objectMapper.writeValueAsString(lovreferanse.tilDto())

    fun fraJson(json: String): Lovreferanse = objectMapper.readValue<LovreferanseDto>(json).tilLovreferanse()
}

private fun LovreferanseDto.tilLovreferanse(): Lovreferanse =
    Lovreferanse(
        lov = this.lov,
        paragraf = this.paragraf,
        avsnitt = this.avsnitt,
        setning = this.setning,
        bokstav = this.bokstav,
        iKraftFra = this.iKraftFra,
    )

private fun Lovreferanse.tilDto(): LovreferanseDto =
    LovreferanseDto(
        lov = lov,
        paragraf = paragraf,
        avsnitt = avsnitt,
        setning = setning,
        bokstav = bokstav,
        iKraftFra = iKraftFra,
    )

private fun Vurderingskilde.tilDto(): VurderingskildeDto =
    when (this) {
        is Vurderingskilde.Automatisk ->
            VurderingskildeDto.Automatisk(
                prøvingId = medlemskapsprøvingId.value,
                grunnlag = grunnlag.tilDto(),
                utledet = utledetFakta.tilDto(),
                versjonAvKildekode = versjonAvKildekode,
            )

        is Vurderingskilde.Saksbehandler ->
            VurderingskildeDto.Saksbehandler(ident = ident, fritekstbegrunnelse = fritekstbegrunnelse, journalpostId = journalpostId)

        is Vurderingskilde.OverførtFraSpleis ->
            VurderingskildeDto.OverførtFraSpleis(grunnlag = grunnlag.tilDto(), utledet = utledetFakta.tilDto())
    }

private fun VurderingskildeDto.tilVurderingskilde(): Vurderingskilde =
    when (this) {
        is VurderingskildeDto.Automatisk ->
            Vurderingskilde.Automatisk(
                medlemskapsprøvingId = MedlemskapsprøvingId(prøvingId),
                grunnlag = grunnlag.tilMedlemskapsgrunnlag(),
                utledetFakta = utledet.tilUtledet(),
                versjonAvKildekode = versjonAvKildekode,
            )

        is VurderingskildeDto.Saksbehandler ->
            Vurderingskilde.Saksbehandler(ident = ident, fritekstbegrunnelse = fritekstbegrunnelse, journalpostId = journalpostId)

        is VurderingskildeDto.OverførtFraSpleis ->
            Vurderingskilde.OverførtFraSpleis(grunnlag = grunnlag.tilMedlemskapsgrunnlag(), utledetFakta = utledet.tilUtledet())
    }

private fun Medlemskapsgrunnlag.tilDto(): MedlemskapsgrunnlagDto =
    when (this) {
        is Medlemskapsgrunnlag.Arbeidstaker -> MedlemskapsgrunnlagDto.Arbeidstaker(arbeidsforhold.map { it.tilDto() })
        Medlemskapsgrunnlag.SelvstendigNæringsdrivende -> MedlemskapsgrunnlagDto.SelvstendigNæringsdrivende()
    }

private fun MedlemskapsgrunnlagDto.tilMedlemskapsgrunnlag(): Medlemskapsgrunnlag =
    when (this) {
        is MedlemskapsgrunnlagDto.Arbeidstaker -> Medlemskapsgrunnlag.Arbeidstaker(arbeidsforhold.map { it.tilArbeidsforhold() })
        is MedlemskapsgrunnlagDto.SelvstendigNæringsdrivende -> Medlemskapsgrunnlag.SelvstendigNæringsdrivende
    }

private fun UtledetFakta.tilDto(): UtledetDto =
    when (this) {
        UtledetFakta.Ingen -> UtledetDto.IngenUtledning()
        is UtledetFakta.Medlemskapstid ->
            UtledetDto.Medlemskapstid(
                medlemskapsperiodeFom = medlemskapsperiode?.start,
                medlemskapsperiodeTom = medlemskapsperiode?.endInclusive,
                medlemskapsdager = medlemskapsdager,
            )
    }

private fun UtledetDto.tilUtledet(): UtledetFakta =
    when (this) {
        is UtledetDto.IngenUtledning -> UtledetFakta.Ingen
        is UtledetDto.Medlemskapstid ->
            UtledetFakta.Medlemskapstid(
                medlemskapsperiode =
                    if (medlemskapsperiodeFom != null && medlemskapsperiodeTom != null) {
                        Periode(medlemskapsperiodeFom, medlemskapsperiodeTom)
                    } else {
                        null
                    },
                medlemskapsdager = medlemskapsdager,
            )
    }

private fun Arbeidsforhold.tilDto() =
    ArbeidsforholdDto(
        orgnummer = orgnummer,
        fom = ansettelseperiode.start,
        tom = ansettelseperiode.endInclusive,
        type =
            when (type) {
                Arbeidsforhold.Arbeidsforholdtype.FORENKLET_OPPGJØRSORDNING -> ArbeidsforholdtypeDto.FORENKLET_OPPGJØRSORDNING
                Arbeidsforhold.Arbeidsforholdtype.FRILANSER -> ArbeidsforholdtypeDto.FRILANSER
                Arbeidsforhold.Arbeidsforholdtype.MARITIMT -> ArbeidsforholdtypeDto.MARITIMT
                Arbeidsforhold.Arbeidsforholdtype.ORDINÆRT -> ArbeidsforholdtypeDto.ORDINÆRT
                Arbeidsforhold.Arbeidsforholdtype.UKJENT -> ArbeidsforholdtypeDto.UKJENT
            },
    )

private fun ArbeidsforholdDto.tilArbeidsforhold() =
    Arbeidsforhold(
        orgnummer = orgnummer,
        ansettelseperiode = Periode(fom, tom),
        type =
            when (type) {
                ArbeidsforholdtypeDto.FORENKLET_OPPGJØRSORDNING -> Arbeidsforhold.Arbeidsforholdtype.FORENKLET_OPPGJØRSORDNING
                ArbeidsforholdtypeDto.FRILANSER -> Arbeidsforhold.Arbeidsforholdtype.FRILANSER
                ArbeidsforholdtypeDto.MARITIMT -> Arbeidsforhold.Arbeidsforholdtype.MARITIMT
                ArbeidsforholdtypeDto.ORDINÆRT -> Arbeidsforhold.Arbeidsforholdtype.ORDINÆRT
                ArbeidsforholdtypeDto.UKJENT -> Arbeidsforhold.Arbeidsforholdtype.UKJENT
            },
    )
