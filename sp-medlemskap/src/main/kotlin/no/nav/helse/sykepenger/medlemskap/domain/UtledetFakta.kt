package no.nav.helse.sykepenger.medlemskap.domain

import no.nav.helse.Periode

internal sealed interface UtledetFakta {
    data object Ingen : UtledetFakta

    data class Medlemskapstid(
        val medlemskapsperiode: Periode?,
        val medlemskapsdager: Int,
    ) : UtledetFakta
}
