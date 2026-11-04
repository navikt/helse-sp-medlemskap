package no.nav.helse.sykepenger.medlemskap.domain

import no.nav.helse.Periode
import java.util.UUID

/** Hvor en [Medlemskapsvurdering] kommer fra. */
internal sealed interface Vurderingskilde {
    /**
     * Vurdert av sp-medlemskap ut fra svaret fra medlemskapstjenesten for [oppslagsperiode]. Er
     * [forhåndsvurdertSøknadId] satt, kom svaret fra en [Forhåndsvurdering] av den søknaden, ikke fra
     * et oppslag. [forhåndsvurderingId] peker på den forhåndsvurderingen som ble brukt. Den mangler på
     * vurderinger gjort før id-en fantes.
     */
    data class Automatisk(
        val medlemskapsprøvingId: MedlemskapsprøvingId,
        val oppslagsperiode: Periode,
        val versjonAvKildekode: String,
        val aarsaker: List<Årsak>? = null,
        val forhåndsvurdertSøknadId: UUID? = null,
        val forhåndsvurderingId: ForhåndsvurderingId? = null,
    ) : Vurderingskilde

    data class Saksbehandler(
        val ident: String,
        val fritekstbegrunnelse: String,
        val journalpostId: List<String>,
    ) : Vurderingskilde

    data object OverførtFraSpleis : Vurderingskilde

    data object OverførtFraInfotrygd : Vurderingskilde
}
