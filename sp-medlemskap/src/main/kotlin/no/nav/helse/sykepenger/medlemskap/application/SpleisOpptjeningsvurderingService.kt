package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.tilMedlemskapsvurdering

internal class SpleisMedlemskapsvurderingService(
    private val spleisClient: ISpleisClient,
) {
    fun finn(
        medlemskapsvurderingId: MedlemskapsvurderingId,
        fødselsnummer: String,
    ): Medlemskapsvurdering? =
        spleisClient
            .hentMedlemskapsvurderinger(fødselsnummer)
            .find { it.medlemskapsvurderingId == medlemskapsvurderingId }
            ?.tilMedlemskapsvurdering(fødselsnummer)
}
