package no.nav.helse.sykepenger.medlemskap.infra.spleis

import no.nav.helse.Periode
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.til
import java.time.Instant
import java.time.LocalDate

internal sealed interface SpleisMedlemskapsvurdering {
    val medlemskapsvurderingId: MedlemskapsvurderingId
    val skjæringstidspunkt: LocalDate
    val opprettet: Instant

    data class SpleisArbeidstaker(
        override val medlemskapsvurderingId: MedlemskapsvurderingId,
        override val skjæringstidspunkt: LocalDate,
        override val opprettet: Instant,
        val oppfylt: Boolean,
        val antallDager: Int,
        val medlemskapsperiode: Periode?,
        val arbeidsforhold: List<Arbeidsforhold>,
    ) : SpleisMedlemskapsvurdering {
        data class Arbeidsforhold(
            val organisasjonsnummer: String,
            val ansettelsesperioder: List<Ansettelsesperiode>,
        )

        data class Ansettelsesperiode(
            val fom: LocalDate,
            val tom: LocalDate?,
        ) {
            init {
                tom?.let { fom til tom }
            }
        }
    }

    data class SpleisSelvstendig(
        override val medlemskapsvurderingId: MedlemskapsvurderingId,
        override val opprettet: Instant,
        override val skjæringstidspunkt: LocalDate,
    ) : SpleisMedlemskapsvurdering

    data class InfotrygdArbeidstaker(
        override val medlemskapsvurderingId: MedlemskapsvurderingId,
        override val opprettet: Instant,
        override val skjæringstidspunkt: LocalDate,
    ) : SpleisMedlemskapsvurdering
}
