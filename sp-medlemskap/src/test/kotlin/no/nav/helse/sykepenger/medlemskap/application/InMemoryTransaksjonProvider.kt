package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.TransaksjonProvider

internal class InMemoryTransaksjonProvider(
    override val medlemskapsprøvinger: InMemoryMedlemskapsprøvingRepository = InMemoryMedlemskapsprøvingRepository(),
    override val medlemskapsvurderinger: InMemoryMedlemskapsvurderingRepository = InMemoryMedlemskapsvurderingRepository(),
    override val migreringslogg: InMemoryMigreringsloggRepository = InMemoryMigreringsloggRepository(),
    override val outbox: InMemoryOutbox = InMemoryOutbox(),
) : TransaksjonProvider<Transaksjonskontekst>,
    Transaksjonskontekst {
    override fun <T> transaksjon(block: (Transaksjonskontekst) -> T): T = block(this)
}
