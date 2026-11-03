package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import java.time.Instant

/**
 * Et behov for medlemskapsvurdering fra Spleis som holdes tilbake mens prøvingen venter på en
 * forhåndsvurdering for skjæringstidspunktet. Kommer ikke forhåndsvurderingen
 * innen [frist], spørres medlemskapstjenesten direkte, se [MedlemskapService.behandleVentendeBehov].
 *
 * [opprinneligBehov] er behovet slik Spleis sendte det. Det er det vi til slutt svarer på.
 */
internal data class VentendeBehov(
    val medlemskapsprøvingId: MedlemskapsprøvingId,
    val fødselsnummer: String,
    val vedtaksperiodeId: String,
    val opprinneligBehov: String,
    val frist: Instant,
)

internal interface VentendeBehovRepository {
    /** Lagrer behovet, eller erstatter det som allerede venter for samme prøving. */
    fun lagre(behov: VentendeBehov)

    fun finn(medlemskapsprøvingId: MedlemskapsprøvingId): VentendeBehov?

    /** Id-ene til prøvingene som har ventende behov, eldste først. */
    fun alle(maksAntall: Int): List<MedlemskapsprøvingId>

    fun fjern(medlemskapsprøvingId: MedlemskapsprøvingId)
}
