package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.Periode
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import java.time.LocalDate

internal sealed class VurderMedlemskapResultat {
    data class HarVurdering(
        val fødselsnummer: String,
        val skjæringstidspunkt: LocalDate,
        val medlemskapsvurderingId: MedlemskapsvurderingId,
    ) : VurderMedlemskapResultat()

    /** Prøvingen venter på svar fra medlemskapstjenesten; kalleren må be om det med behovet `Medlemskap`. */
    data class TrengerMedlemskapsopplysninger(
        val fødselsnummer: String,
        val skjæringstidspunkt: LocalDate,
        val medlemskapsprøvingId: MedlemskapsprøvingId,
        val oppslagsperiode: Periode,
    ) : VurderMedlemskapResultat()
}
