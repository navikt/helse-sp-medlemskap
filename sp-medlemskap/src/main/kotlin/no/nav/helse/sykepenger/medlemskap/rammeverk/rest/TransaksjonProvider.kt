package no.nav.helse.sykepenger.medlemskap.rammeverk.rest

interface TransaksjonProvider<TRANSAKSJON> {
    fun <T> transaksjon(block: (TRANSAKSJON) -> T): T
}
