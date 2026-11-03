package no.nav.helse.sykepenger.medlemskap.domain

import no.nav.helse.Periode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Vurdering medlemskapstjenesten (LovMe) gjør av seg selv når en førstegangssøknad kommer inn, og
 * sender på topicet `medlemskap.medlemskapsvurderinger`. Påfølgende søknader og korrigeringer blir
 * ikke vurdert.
 *
 * Forhåndsvurderingen kan ta hensyn til svarene bruker har gitt i søknaden. Et synkront oppslag gjort
 * før forhåndsvurderingen er ferdig, kan derfor gi `UAVKLART_MED_BRUKERSPORSMAAL` der
 * forhåndsvurderingen ville gitt `JA`.
 *
 * [gyldigForSkjæringstidspunkt] er perioden medlemskapstjenesten sier vurderingen gjelder for. Den er
 * romslig, fordi medlemskapstjenesten ikke regner ut skjæringstidspunktet slik Spleis gjør. [søknadId]
 * er søknadens id hos Flex, og er bare med for sporing.
 */
internal data class Forhåndsvurdering(
    val søknadId: UUID,
    val fødselsnummer: String,
    val gyldigForSkjæringstidspunkt: Periode?,
    val status: Medlemskapsstatus,
    val aarsaker: List<Årsak>?,
    val mottatt: Instant,
) {
    /** Forhåndsvurderingen kan bare brukes for skjæringstidspunkter i perioden den gjelder for. */
    fun gjelder(skjæringstidspunkt: LocalDate) = gyldigForSkjæringstidspunkt?.contains(skjæringstidspunkt) == true

    companion object {
        /** Den sist mottatte forhåndsvurderingen som gjelder skjæringstidspunktet. */
        fun List<Forhåndsvurdering>.gjeldendeFor(skjæringstidspunkt: LocalDate) = filter { it.gjelder(skjæringstidspunkt) }.maxByOrNull { it.mottatt }
    }
}
