package no.nav.helse.sykepenger.medlemskap.infra.spleis

import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering

internal fun SpleisMedlemskapsvurdering.tilMedlemskapsvurdering(fødselsnummer: String): Medlemskapsvurdering =
    when (this) {
        is SpleisMedlemskapsvurdering.Spleis ->
            Medlemskapsvurdering.overførtFraSpleis(
                id = medlemskapsvurderingId,
                fødselsnummer = fødselsnummer,
                skjæringstidspunkt = skjæringstidspunkt,
                status = status,
                vurdertTidspunkt = opprettet,
            )

        is SpleisMedlemskapsvurdering.Infotrygd ->
            Medlemskapsvurdering.overførtFraInfotrygd(
                id = medlemskapsvurderingId,
                fødselsnummer = fødselsnummer,
                skjæringstidspunkt = skjæringstidspunkt,
                vurdertTidspunkt = opprettet,
            )
    }
