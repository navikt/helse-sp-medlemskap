package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.sykepenger.medlemskap.domain.Forhåndsvurdering

internal interface ForhåndsvurderingRepository {
    fun lagre(
        forhåndsvurdering: Forhåndsvurdering,
        melding: String,
    )

    /** Alle forhåndsvurderingene av personen. */
    fun finn(fødselsnummer: String): List<Forhåndsvurdering>
}
