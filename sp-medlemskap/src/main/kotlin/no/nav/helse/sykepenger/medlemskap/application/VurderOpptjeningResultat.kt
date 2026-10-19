package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import java.time.LocalDate

internal sealed class VurderMedlemskapResultat {
    data class HarVurdering(
        val fødselsnummer: String,
        val skjæringstidspunkt: LocalDate,
        val medlemskapsvurderingId: MedlemskapsvurderingId,
    ) : VurderMedlemskapResultat()

    data class TrengerArbeidsforhold(
        val fødselsnummer: String,
        val skjæringstidspunkt: LocalDate,
    ) : VurderMedlemskapResultat()
}
