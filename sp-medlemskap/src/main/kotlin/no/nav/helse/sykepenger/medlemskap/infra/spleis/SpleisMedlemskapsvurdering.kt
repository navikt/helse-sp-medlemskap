package no.nav.helse.sykepenger.medlemskap.infra.spleis

import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import java.time.Instant
import java.time.LocalDate

internal sealed interface SpleisMedlemskapsvurdering {
    val medlemskapsvurderingId: MedlemskapsvurderingId
    val skjæringstidspunkt: LocalDate
    val opprettet: Instant

    /** Medlemskapsstatusen Spleis har lagt til grunn for vilkårsgrunnlaget. */
    data class Spleis(
        override val medlemskapsvurderingId: MedlemskapsvurderingId,
        override val skjæringstidspunkt: LocalDate,
        override val opprettet: Instant,
        val status: Medlemskapsstatus,
    ) : SpleisMedlemskapsvurdering

    /** Vilkårsgrunnlag fra Infotrygd; Spleis kjenner ikke til hvordan medlemskap ble vurdert der. */
    data class Infotrygd(
        override val medlemskapsvurderingId: MedlemskapsvurderingId,
        override val skjæringstidspunkt: LocalDate,
        override val opprettet: Instant,
    ) : SpleisMedlemskapsvurdering
}
