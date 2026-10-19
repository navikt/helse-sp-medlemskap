package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsprøving
import java.time.LocalDate

internal interface MedlemskapsprøvingRepository {
    fun lagre(prøving: Medlemskapsprøving)

    fun finnSiste(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ): Medlemskapsprøving?
}
