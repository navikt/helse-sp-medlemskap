package no.nav.helse.sykepenger.medlemskap.domain

import java.time.Instant

internal data class Vilkårsvurdering(
    val id: VilkårsvurderingId,
    val vilkårskode: Vilkårskode,
    val utfall: Utfall,
    val vurdertTidspunkt: Instant,
    val kilde: Vurderingskilde,
    val vurderingskilde: Medlemskapsvurderingskilde,
    val lovreferanse: Lovreferanse,
) {
    private fun erOppfylt() = utfall == Utfall.Oppfylt

    companion object {
        fun List<Vilkårsvurdering>.finn(vilkårskode: Vilkårskode): Vilkårsvurdering? = this.find { it.vilkårskode == vilkårskode }

        fun List<Vilkårsvurdering>.avgjørendeVilkårsvurdering(): Vilkårsvurdering? {
            val hovedregel = this.finn(Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER) ?: return null
            if (hovedregel.erOppfylt()) return hovedregel
            return null
        }

        internal fun automatisk(
            medlemskapsprøvingId: MedlemskapsprøvingId,
            vilkårsutfall: Vilkårsutfall,
            grunnlag: Medlemskapsgrunnlag,
            versjonAvKildekode: String,
            vurdertTidspunkt: Instant,
        ) = Vilkårsvurdering(
            id = VilkårsvurderingId.ny(),
            vilkårskode = vilkårsutfall.vilkårskode,
            utfall = vilkårsutfall.utfall,
            vurdertTidspunkt = vurdertTidspunkt,
            kilde = Vurderingskilde.Automatisk(medlemskapsprøvingId, grunnlag, vilkårsutfall.utledetFakta, versjonAvKildekode),
            vurderingskilde = Medlemskapsvurderingskilde.VURDERT_I_SP_medlemskap,
            lovreferanse = vilkårsutfall.lovreferanse,
        )

        fun avSaksbehandler(
            vilkårskode: Vilkårskode,
            utfall: Utfall,
            saksbehandlerIdent: String,
            fritekstbegrunnelse: String,
            journalpostId: List<String> = emptyList(),
        ) = Vilkårsvurdering(
            id = VilkårsvurderingId.ny(),
            vilkårskode = vilkårskode,
            utfall = utfall,
            vurdertTidspunkt = Instant.now(),
            kilde = Vurderingskilde.Saksbehandler(saksbehandlerIdent, fritekstbegrunnelse, journalpostId),
            vurderingskilde = Medlemskapsvurderingskilde.VURDERT_I_SP_medlemskap,
            lovreferanse = vilkårskode.lovreferanse,
        )

        fun overførtFraSpleis(
            vilkårskode: Vilkårskode,
            utfall: Utfall,
            grunnlag: Medlemskapsgrunnlag,
            utledetFakta: UtledetFakta,
            vurdertTidspunkt: Instant,
        ) = Vilkårsvurdering(
            id = VilkårsvurderingId.ny(),
            vilkårskode = vilkårskode,
            utfall = utfall,
            vurdertTidspunkt = vurdertTidspunkt,
            kilde = Vurderingskilde.OverførtFraSpleis(grunnlag, utledetFakta),
            vurderingskilde = Medlemskapsvurderingskilde.OVERFORT_FRA_SPLEIS,
            lovreferanse = vilkårskode.lovreferanse,
        )

        fun fraLagring(
            id: VilkårsvurderingId,
            vilkårskode: Vilkårskode,
            utfall: Utfall,
            vurdertTidspunkt: Instant,
            kilde: Vurderingskilde,
            vurderingskilde: Medlemskapsvurderingskilde,
            lovreferanse: Lovreferanse,
        ) = Vilkårsvurdering(id, vilkårskode, utfall, vurdertTidspunkt, kilde, vurderingskilde, lovreferanse)
    }
}
