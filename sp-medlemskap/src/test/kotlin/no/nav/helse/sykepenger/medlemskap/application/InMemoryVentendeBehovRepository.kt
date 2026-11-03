package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId

internal class InMemoryVentendeBehovRepository : VentendeBehovRepository {
    private val behov = linkedMapOf<MedlemskapsprøvingId, VentendeBehov>()

    internal val alleBehov: List<VentendeBehov> get() = behov.values.toList()

    override fun lagre(behov: VentendeBehov) {
        this.behov[behov.medlemskapsprøvingId] = behov
    }

    override fun finn(medlemskapsprøvingId: MedlemskapsprøvingId) = behov[medlemskapsprøvingId]

    override fun alle(maksAntall: Int) = behov.keys.take(maksAntall)

    override fun fjern(medlemskapsprøvingId: MedlemskapsprøvingId) {
        behov.remove(medlemskapsprøvingId)
    }
}
