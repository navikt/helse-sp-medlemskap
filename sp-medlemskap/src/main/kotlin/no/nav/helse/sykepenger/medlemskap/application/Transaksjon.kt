package no.nav.helse.sykepenger.medlemskap.application

internal interface Transaksjonskontekst {
    val medlemskapsprøvinger: MedlemskapsprøvingRepository
    val medlemskapsvurderinger: MedlemskapsvurderingRepository
    val migreringslogg: MigreringsloggRepository
    val outbox: Outbox
}
