package no.nav.helse.sykepenger.medlemskap.domain

internal sealed interface Vurderingskilde {
    data class Automatisk(
        val medlemskapsprøvingId: MedlemskapsprøvingId,
        val grunnlag: Medlemskapsgrunnlag,
        val utledetFakta: UtledetFakta,
        val versjonAvKildekode: String,
    ) : Vurderingskilde

    data class Saksbehandler(
        val ident: String,
        val fritekstbegrunnelse: String,
        val journalpostId: List<String>,
    ) : Vurderingskilde

    data class OverførtFraSpleis(
        val grunnlag: Medlemskapsgrunnlag,
        val utledetFakta: UtledetFakta,
    ) : Vurderingskilde
}
