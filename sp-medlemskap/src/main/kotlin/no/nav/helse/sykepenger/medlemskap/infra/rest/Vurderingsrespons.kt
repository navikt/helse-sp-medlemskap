package no.nav.helse.sykepenger.medlemskap.infra.rest

import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.Vurderingskilde
import java.time.LocalDate

internal object Vurderingsrespons {
    fun fra(vurdering: Medlemskapsvurdering): ApiVilkårsvurderingerForPersonResponse =
        ApiVilkårsvurderingerForPersonResponse(
            skjæringstidspunkt = vurdering.skjæringstidspunkt,
            krav = listOf(vurdering.tilApi()),
        )

    fun historikk(
        skjæringstidspunkt: LocalDate,
        vurderinger: List<Medlemskapsvurdering>,
    ): ApiMedlemskapshistorikkResponse =
        ApiMedlemskapshistorikkResponse(
            skjæringstidspunkt = skjæringstidspunkt,
            historikk =
                vurderinger.map {
                    ApiMedlemskapshistorikkInnslag(vurdertTidspunkt = it.vurdertTidspunkt, medlemskapsvurdering = it.tilApi())
                },
        )
}

private fun Medlemskapsvurdering.tilApi() =
    ApiMedlemskapsvurdering(
        id = id.value,
        kravkode = ApiKravkode.MEDLEMSKAP,
        medlemskapOk = erOk,
        status = status.tilApi(),
        vurdertTidspunkt = vurdertTidspunkt,
        kilde = kilde.tilApi(),
    )

private fun Medlemskapsstatus.tilApi(): ApiMedlemskapsstatus =
    when (this) {
        Medlemskapsstatus.Ja -> ApiMedlemskapsstatus.JA
        Medlemskapsstatus.Nei -> ApiMedlemskapsstatus.NEI
        Medlemskapsstatus.Uavklart -> ApiMedlemskapsstatus.UAVKLART
        Medlemskapsstatus.UavklartMedBrukerspørsmål -> ApiMedlemskapsstatus.UAVKLART_MED_BRUKERSPORSMAAL
    }

private fun Vurderingskilde.tilApi(): ApiVurderingskilde =
    when (this) {
        is Vurderingskilde.Automatisk ->
            ApiVurderingskilde.Automatisk(
                versjonAvKildekode = versjonAvKildekode,
                oppslagsperiode = ApiPeriode(fom = oppslagsperiode.start, tom = oppslagsperiode.endInclusive),
                årsaker = aarsaker.orEmpty().map { ApiÅrsak(regelId = it.regelId, forklaringPaaUavklart = it.forklaringPaaUavklart) },
            )

        is Vurderingskilde.Saksbehandler ->
            ApiVurderingskilde.Saksbehandler(ident = ident, fritekstbegrunnelse = fritekstbegrunnelse, journalpostId = journalpostId)

        Vurderingskilde.OverførtFraSpleis -> ApiVurderingskilde.OverførtFraSpleis()

        Vurderingskilde.OverførtFraInfotrygd -> ApiVurderingskilde.OverførtFraInfotrygd()
    }
