package no.nav.helse.sykepenger.medlemskap.domain

import no.nav.helse.Periode
import no.nav.helse.januar
import no.nav.helse.sykepenger.medlemskap.domain.Vilkårsvurdering.Companion.avgjørendeVilkårsvurdering
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

internal class VilkårsvurderingTest {
    @Test
    fun `Hovedregel er oppfylt`() {
        val vurderinger = listOf(hovedregelOppfylt)
        assertEquals(Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER, vurderinger.avgjørendeVilkårsvurdering()?.vilkårskode)
    }

    @Test
    fun `Hovedregel er ikke oppfylt`() {
        assertEquals(null, listOf(hovedregelIkkeOppfylt).avgjørendeVilkårsvurdering()?.vilkårskode)
    }

    val hovedregelOppfylt = nyVurdering(Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER, Utfall.Oppfylt)
    val hovedregelIkkeOppfylt = nyVurdering(Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER, Utfall.IkkeOppfylt)

    operator fun Vilkårsvurdering.plus(other: Vilkårsvurdering): List<Vilkårsvurdering> = listOf(this, other)

    private fun nyVurdering(
        vilkårskode: Vilkårskode,
        utfall: Utfall,
    ) = Vilkårsvurdering(
        id = VilkårsvurderingId.ny(),
        vilkårskode = vilkårskode,
        utfall = utfall,
        vurdertTidspunkt = Instant.now(),
        kilde =
            Vurderingskilde.Automatisk(
                medlemskapsprøvingId = MedlemskapsprøvingId.ny(),
                grunnlag = Medlemskapsgrunnlag.Arbeidstaker(listOf(Arbeidsforhold("123456789", Periode(1.januar, 31.januar), Arbeidsforhold.Arbeidsforholdtype.ORDINÆRT))),
                utledetFakta = UtledetFakta.Medlemskapstid(Periode(1.januar, 31.januar), 30),
                versjonAvKildekode = "En versjon",
            ),
        vurderingskilde = Medlemskapsvurderingskilde.VURDERT_I_SP_medlemskap,
        lovreferanse = Lovreferanse.`§ 8-2 første avsnitt, første setning`(),
    )
}
