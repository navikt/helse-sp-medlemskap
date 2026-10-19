package no.nav.helse.sykepenger.medlemskap.infra.db

import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.domain.Vurderingskilde
import no.nav.helse.til
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

private val objectMapper = jacksonObjectMapper()

/**
 * Kilden lagres som json i én kolonne. Selve json-en er selvbeskrivende: hvilken dto den skal leses
 * som følger av `type`-diskriminatoren.
 */
internal object Vurderingskildejson {
    fun tilJson(kilde: Vurderingskilde): String = objectMapper.writeValueAsString(kilde.tilDto())

    fun fraJson(json: String): Vurderingskilde = objectMapper.readValue<VurderingskildeDto>(json).tilVurderingskilde()
}

private fun Vurderingskilde.tilDto(): VurderingskildeDto =
    when (this) {
        is Vurderingskilde.Automatisk ->
            VurderingskildeDto.Automatisk(
                prøvingId = medlemskapsprøvingId.value,
                oppslagFom = oppslagsperiode.start,
                oppslagTom = oppslagsperiode.endInclusive,
                versjonAvKildekode = versjonAvKildekode,
            )

        is Vurderingskilde.Saksbehandler ->
            VurderingskildeDto.Saksbehandler(ident = ident, fritekstbegrunnelse = fritekstbegrunnelse, journalpostId = journalpostId)

        Vurderingskilde.OverførtFraSpleis -> VurderingskildeDto.OverførtFraSpleis()

        Vurderingskilde.OverførtFraInfotrygd -> VurderingskildeDto.OverførtFraInfotrygd()
    }

private fun VurderingskildeDto.tilVurderingskilde(): Vurderingskilde =
    when (this) {
        is VurderingskildeDto.Automatisk ->
            Vurderingskilde.Automatisk(
                medlemskapsprøvingId = MedlemskapsprøvingId(prøvingId),
                oppslagsperiode = oppslagFom til oppslagTom,
                versjonAvKildekode = versjonAvKildekode,
            )

        is VurderingskildeDto.Saksbehandler ->
            Vurderingskilde.Saksbehandler(ident = ident, fritekstbegrunnelse = fritekstbegrunnelse, journalpostId = journalpostId)

        is VurderingskildeDto.OverførtFraSpleis -> Vurderingskilde.OverførtFraSpleis

        is VurderingskildeDto.OverførtFraInfotrygd -> Vurderingskilde.OverførtFraInfotrygd
    }
