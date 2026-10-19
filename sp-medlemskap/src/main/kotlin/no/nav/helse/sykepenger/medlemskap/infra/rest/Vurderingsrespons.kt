package no.nav.helse.sykepenger.medlemskap.infra.rest

import no.nav.helse.Periode
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidsforhold
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsgrunnlag
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.Utfall
import no.nav.helse.sykepenger.medlemskap.domain.UtledetFakta
import no.nav.helse.sykepenger.medlemskap.domain.Vilkårskode
import no.nav.helse.sykepenger.medlemskap.domain.Vilkårsvurdering
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

private fun Medlemskapsvurdering.tilApi(): ApiMedlemskapsvurdering =
    when (this) {
        is Medlemskapsvurdering.OverførtFraInfotrygd ->
            ApiMedlemskapsvurdering.OverførtFraInfotrygd(
                id = id.value,
                kravkode = ApiKravkode.MEDLEMSKAP,
                medlemskapOk = erOk,
            )

        is Medlemskapsvurdering.OverførtFraSpleis ->
            ApiMedlemskapsvurdering.OverførtFraSpleis(
                id = id.value,
                kravkode = ApiKravkode.MEDLEMSKAP,
                medlemskapOk = erOk,
                avgjørendeVilkårskode = avgjørendeVilkårskode?.tilApi(),
                vurderinger = vilkårsvurderinger.map { it.tilApi() },
            )

        is Medlemskapsvurdering.VurdertISpVilkårsprøving ->
            ApiMedlemskapsvurdering.VurdertISpVilkarproving(
                id = id.value,
                kravkode = ApiKravkode.MEDLEMSKAP,
                medlemskapOk = erOk,
                avgjørendeVilkårskode = avgjørendeVilkårskode?.tilApi(),
                vurderinger = vilkårsvurderinger.map { it.tilApi() },
            )
    }

private fun Vilkårsvurdering.tilApi() =
    ApiVilkårsvurdering(
        id = id.value,
        vilkårskode = vilkårskode.tilApi(),
        utfall = utfall.tilApi(),
        vurdertTidspunkt = vurdertTidspunkt,
        lovreferanse = lovreferanse.tilApi(),
        kilde = kilde.tilApi(),
    )

private fun no.nav.helse.sykepenger.medlemskap.domain.Lovreferanse.tilApi() =
    ApiLovreferanse(
        lov = lov,
        paragraf = paragraf,
        avsnitt = avsnitt,
        setning = setning,
        bokstav = bokstav,
        iKraftFra = iKraftFra,
    )

private fun Utfall.tilApi(): ApiUtfall =
    when (this) {
        Utfall.Oppfylt -> ApiUtfall.OPPFYLT
        Utfall.IkkeOppfylt -> ApiUtfall.IKKE_OPPFYLT
    }

private fun Vilkårskode.tilApi(): ApiVilkårskode =
    when (this) {
        Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER -> ApiVilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER
    }

private fun Vurderingskilde.tilApi(): ApiVurderingskilde =
    when (this) {
        is Vurderingskilde.Automatisk ->
            ApiVurderingskilde.Automatisk(
                versjonAvKildekode = versjonAvKildekode,
                grunnlag = grunnlag.tilApi(utledetFakta),
            )

        is Vurderingskilde.Saksbehandler ->
            ApiVurderingskilde.Saksbehandler(ident = ident, fritekstbegrunnelse = fritekstbegrunnelse, journalpostId = journalpostId)

        is Vurderingskilde.OverførtFraSpleis ->
            ApiVurderingskilde.OverførtFraSpleis(grunnlag = grunnlag.tilApi(utledetFakta))
    }

private fun Medlemskapsgrunnlag.tilApi(utledetFakta: UtledetFakta): ApiVurderingsgrunnlag =
    when (this) {
        is Medlemskapsgrunnlag.Arbeidstaker -> {
            check(utledetFakta is UtledetFakta.Medlemskapstid) {
                "Arbeidstaker-grunnlag skal ha utledet medlemskapstid, fikk $utledetFakta"
            }
            ApiVurderingsgrunnlag.Arbeidsforhold(
                arbeidsforhold = arbeidsforhold.map { it.tilApi() },
                medlemskapsperiode = utledetFakta.medlemskapsperiode?.tilApi(),
                medlemskapsdager = utledetFakta.medlemskapsdager,
            )
        }

        Medlemskapsgrunnlag.SelvstendigNæringsdrivende -> ApiVurderingsgrunnlag.SelvstendigNæringsdrivende()
    }

private fun Periode.tilApi() = ApiPeriode(fom = start, tom = endInclusive)

private fun Arbeidsforhold.tilApi() =
    ApiArbeidsforhold(
        organisasjonsnummer = orgnummer,
        fom = ansettelseperiode.start,
        tom = ansettelseperiode.endInclusive.takeUnless { it == LocalDate.MAX },
        type =
            when (type) {
                Arbeidsforhold.Arbeidsforholdtype.FORENKLET_OPPGJØRSORDNING -> ApiArbeidsforholdtype.FORENKLET_OPPGJØRSORDNING
                Arbeidsforhold.Arbeidsforholdtype.FRILANSER -> ApiArbeidsforholdtype.FRILANSER
                Arbeidsforhold.Arbeidsforholdtype.MARITIMT -> ApiArbeidsforholdtype.MARITIMT
                Arbeidsforhold.Arbeidsforholdtype.ORDINÆRT -> ApiArbeidsforholdtype.ORDINÆRT
                Arbeidsforhold.Arbeidsforholdtype.UKJENT -> ApiArbeidsforholdtype.UKJENT
            },
    )
