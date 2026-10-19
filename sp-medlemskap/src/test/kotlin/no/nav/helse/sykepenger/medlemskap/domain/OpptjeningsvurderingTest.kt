package no.nav.helse.sykepenger.medlemskap.domain

import no.nav.helse.februar
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class MedlemskapsvurderingTest {
    @Test
    fun `infotrygdvurdering har ingen vilkårsvurderinger, kun et utfall`() {
        // given
        val vurdering =
            Medlemskapsvurdering.fraInfotrygd(
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
                erOk = true,
                vurdertTidspunkt = Instant.now(),
            )

        // then
        assertIs<Medlemskapsvurdering.OverførtFraInfotrygd>(vurdering)
        assertTrue(vurdering.erOk)
    }

    @Test
    fun `saksbehandlervurdering har vilkårsvurderinger uten prøving`() {
        // given
        val vilkårsvurdering =
            Vilkårsvurdering.avSaksbehandler(
                vilkårskode = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                utfall = Utfall.Oppfylt,
                saksbehandlerIdent = "Z999999",
                fritekstbegrunnelse = "vurdert etter dialog med bruker",
            )

        // when
        val vurdering =
            Medlemskapsvurdering.avSaksbehandler(
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
                vilkårsvurdering = vilkårsvurdering,
            )

        // then
        assertTrue(vurdering.erOk)
        assertEquals(vilkårsvurdering, vurdering.avgjørendeVilkårsvurdering)
        val kilde = vurdering.vilkårsvurderinger.single().kilde
        assertIs<Vurderingskilde.Saksbehandler>(kilde)
        assertEquals("Z999999", kilde.ident)
    }

    @Test
    fun `en infotrygdvurdering har ingen vilkårsvurderinger å videreføre`() {
        val forrige =
            Medlemskapsvurdering.fraInfotrygd(
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
                erOk = false,
                vurdertTidspunkt = Instant.now(),
            )

        val vurdering =
            Medlemskapsvurdering.avSaksbehandler(
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
                vilkårsvurdering =
                    Vilkårsvurdering.avSaksbehandler(
                        vilkårskode = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                        utfall = Utfall.Oppfylt,
                        saksbehandlerIdent = "Z999999",
                        fritekstbegrunnelse = "hadde dagpenger i forkant",
                    ),
                forrigeVurdering = forrige,
            )

        assertEquals(1, vurdering.vilkårsvurderinger.size)
    }

    @Test
    fun `manuell vurdering viderefører kategori fra forrige vurdering`() {
        val forrige =
            checkNotNull(
                Medlemskapsprøving
                    .start(
                        fødselsnummer = FØDSELSNUMMER,
                        skjæringstidspunkt = 1.februar,
                        arbeidssituasjon = Arbeidssituasjon.SelvstendigNæringsdrivende,
                    ).vurdering,
            )

        val vurdering =
            Medlemskapsvurdering.avSaksbehandler(
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
                vilkårsvurdering =
                    Vilkårsvurdering.avSaksbehandler(
                        vilkårskode = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                        utfall = Utfall.IkkeOppfylt,
                        saksbehandlerIdent = "Z999999",
                        fritekstbegrunnelse = "ny vurdering",
                    ),
                forrigeVurdering = forrige,
            )

        assertEquals(Kategori.SelvstendigNæringsdrivende, vurdering.kategori)
    }

    private companion object {
        const val FØDSELSNUMMER = "12029240045"
    }
}
