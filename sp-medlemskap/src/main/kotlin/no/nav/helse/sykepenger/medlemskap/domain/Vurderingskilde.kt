package no.nav.helse.sykepenger.medlemskap.domain

import no.nav.helse.Periode

/** Hvor en [Medlemskapsvurdering] kommer fra. */
internal sealed interface Vurderingskilde {
    /** Vurdert av sp-medlemskap ut fra svaret fra medlemskapstjenesten for [oppslagsperiode]. */
    data class Automatisk(
        val medlemskapsprøvingId: MedlemskapsprøvingId,
        val oppslagsperiode: Periode,
        val versjonAvKildekode: String,
        val aarsaker: List<Årsak>? = null,
    ) : Vurderingskilde

    data class Saksbehandler(
        val ident: String,
        val fritekstbegrunnelse: String,
        val journalpostId: List<String>,
    ) : Vurderingskilde

    data object OverførtFraSpleis : Vurderingskilde

    data object OverførtFraInfotrygd : Vurderingskilde
}
