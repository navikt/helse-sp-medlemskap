package no.nav.helse.sykepenger.medlemskap.infra.spleis

import no.nav.helse.sykepenger.medlemskap.domain.Arbeidsforhold
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsgrunnlag
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.Utfall
import no.nav.helse.sykepenger.medlemskap.domain.UtledetFakta
import no.nav.helse.sykepenger.medlemskap.domain.Vilkårskode
import no.nav.helse.sykepenger.medlemskap.domain.Vilkårsvurdering

internal fun SpleisMedlemskapsvurdering.tilMedlemskapsvurdering(fødselsnummer: String): Medlemskapsvurdering =
    when (this) {
        is SpleisMedlemskapsvurdering.SpleisArbeidstaker -> {
            val grunnlag = Medlemskapsgrunnlag.Arbeidstaker(arbeidsforhold.flatMap { it.tilDomene() })
            val utledetFakta = UtledetFakta.Medlemskapstid(medlemskapsperiode, antallDager)
            Medlemskapsvurdering.overførtFraSpleis(
                id = medlemskapsvurderingId,
                fødselsnummer = fødselsnummer,
                skjæringstidspunkt = skjæringstidspunkt,
                vilkårsvurderinger =
                    listOf(
                        Vilkårsvurdering.overførtFraSpleis(
                            vilkårskode = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                            utfall = if (oppfylt) Utfall.Oppfylt else Utfall.IkkeOppfylt,
                            grunnlag = grunnlag,
                            utledetFakta = utledetFakta,
                            vurdertTidspunkt = opprettet,
                        ),
                    ),
            )
        }

        is SpleisMedlemskapsvurdering.SpleisSelvstendig ->
            Medlemskapsvurdering.overførtFraSpleis(
                id = medlemskapsvurderingId,
                fødselsnummer = fødselsnummer,
                skjæringstidspunkt = skjæringstidspunkt,
                vilkårsvurderinger =
                    listOf(
                        Vilkårsvurdering.overførtFraSpleis(
                            vilkårskode = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                            utfall = Utfall.Oppfylt,
                            grunnlag = Medlemskapsgrunnlag.SelvstendigNæringsdrivende,
                            utledetFakta = UtledetFakta.Ingen,
                            vurdertTidspunkt = opprettet,
                        ),
                    ),
            )

        is SpleisMedlemskapsvurdering.InfotrygdArbeidstaker ->
            Medlemskapsvurdering.fraInfotrygd(
                id = medlemskapsvurderingId,
                fødselsnummer = fødselsnummer,
                skjæringstidspunkt = skjæringstidspunkt,
                erOk = true,
                vurdertTidspunkt = opprettet,
            )
    }

private fun SpleisMedlemskapsvurdering.SpleisArbeidstaker.Arbeidsforhold.tilDomene(): List<Arbeidsforhold> =
    ansettelsesperioder.map { periode ->
        Arbeidsforhold(
            orgnummer = organisasjonsnummer.ifBlank { "UKJENT_PRIVAT" },
            ansattFom = periode.fom,
            ansattTom = periode.tom,
            type = Arbeidsforhold.Arbeidsforholdtype.UKJENT,
        )
    }
