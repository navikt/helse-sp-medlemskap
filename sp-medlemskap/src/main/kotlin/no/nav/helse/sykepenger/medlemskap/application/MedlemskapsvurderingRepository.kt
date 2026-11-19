package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import java.time.LocalDate

internal interface MedlemskapsvurderingRepository {
    fun lagre(vurdering: Medlemskapsvurdering)

    /**
     * Hindrer andre som tar samme lås, i å komme forbi her før transaksjonen er ferdig. Låsen gjelder ikke
     * [lagre], så den beskytter bare mot andre som også kaller [låsFor].
     */
    fun låsFor(fødselsnummer: String)

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
