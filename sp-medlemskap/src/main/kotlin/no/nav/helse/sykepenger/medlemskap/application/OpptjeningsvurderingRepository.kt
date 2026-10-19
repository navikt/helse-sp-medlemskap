package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import java.time.LocalDate

internal interface MedlemskapsvurderingRepository {
    fun lagre(vurdering: Medlemskapsvurdering)

    fun gjeldende(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ): Medlemskapsvurdering?

    fun historikk(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ): List<Medlemskapsvurdering>

    fun finn(medlemskapsvurderingId: MedlemskapsvurderingId): Medlemskapsvurdering?
}
