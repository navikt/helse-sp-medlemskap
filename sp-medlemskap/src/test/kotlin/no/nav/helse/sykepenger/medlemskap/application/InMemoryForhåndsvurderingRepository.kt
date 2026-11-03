package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.sykepenger.medlemskap.domain.Forhåndsvurdering

internal class InMemoryForhåndsvurderingRepository : ForhåndsvurderingRepository {
    private val forhåndsvurderinger = mutableListOf<Forhåndsvurdering>()

    internal val alle: List<Forhåndsvurdering> get() = forhåndsvurderinger.toList()

    override fun lagre(
        forhåndsvurdering: Forhåndsvurdering,
        melding: String,
    ) {
        forhåndsvurderinger.add(forhåndsvurdering)
    }

    override fun finn(fødselsnummer: String) = forhåndsvurderinger.filter { it.fødselsnummer == fødselsnummer }.reversed()
}
