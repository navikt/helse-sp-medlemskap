package no.nav.helse.sykepenger.medlemskap.infra.rest

import no.nav.helse.Periode
import no.nav.helse.februar
import no.nav.helse.januar
import no.nav.helse.sykepenger.medlemskap.domain.*
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidsforhold.Arbeidsforholdtype.ORDINÆRT
import no.nav.helse.til
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertTrue

internal class VurderingsresponsTest {
    @Test
    fun `arbeidstakerresponsen inneholder vurdert medlemskapsperiode og medlemskapsdager`() {
        val vurdering = automatiskArbeidstakervurdering(4.januar til 31.januar)

        val grunnlag = Vurderingsrespons.fra(vurdering).enesteVurdering().automatiskGrunnlag()

        assertEquals(4.januar, grunnlag.medlemskapsperiode?.fom)
        assertEquals(31.januar, grunnlag.medlemskapsperiode?.tom)
        assertEquals(28, grunnlag.medlemskapsdager)
    }

    @Test
    fun `skjaeringstidspunktet ligger på rota, ikke per medlemskapsvurdering`() {
        val respons = Vurderingsrespons.fra(automatiskArbeidstakervurdering(4.januar til 31.januar))

        assertEquals(1.februar, respons.skjæringstidspunkt)
    }

    @Test
    fun `medlemskapsvurderingen peker på vilkaaret som avgjorde det`() {
        val respons = Vurderingsrespons.fra(automatiskArbeidstakervurdering(4.januar til 31.januar))
        val krav = respons.krav.single() as ApiMedlemskapsvurdering.VurdertISpVilkarproving

        assertEquals(ApiKravkode.MEDLEMSKAP, krav.kravkode)
        assertTrue(krav.medlemskapOk)
        assertEquals(ApiVilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER, krav.avgjørendeVilkårskode)
        assertEquals(listOf(krav.avgjørendeVilkårskode), krav.vurderinger.map { it.vilkårskode })
    }

    @Test
    fun `vilkaarskode og utfall er to uavhengige akser`() {
        val forKortMedlemskap = automatiskArbeidstakervurdering(29.januar til 31.januar)

        val vurdering = Vurderingsrespons.fra(forKortMedlemskap).enesteVurdering()

        assertEquals(ApiUtfall.IKKE_OPPFYLT, vurdering.utfall)
        assertEquals(ApiVilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER, vurdering.vilkårskode)
        assertEquals(ApiLovreferanse("folketrygdloven", "8-2", 1, 1, null, 1.januar(2004)), vurdering.lovreferanse)
    }

    @Test
    fun `manuell vurdering har saksbehandler som kilde og ikke noe grunnlag`() {
        val vilkårsvurdering =
            Vilkårsvurdering.avSaksbehandler(
                vilkårskode = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                utfall = Utfall.Oppfylt,
                saksbehandlerIdent = "Z999999",
                fritekstbegrunnelse = "Mottok foreldrepenger fram til skjæringstidspunktet.",
                journalpostId = listOf("journalpost-1", "journalpost-2"),
            )
        val vurdering =
            Medlemskapsvurdering.avSaksbehandler(
                fødselsnummer = "12345678901",
                skjæringstidspunkt = 1.februar,
                vilkårsvurdering = vilkårsvurdering,
            )

        val api = Vurderingsrespons.fra(vurdering).enesteVurdering()
        val kilde = api.kilde as ApiVurderingskilde.Saksbehandler

        assertEquals(ApiVilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER, api.vilkårskode)
        assertEquals(ApiLovreferanse("folketrygdloven", "8-2", 1, 1, null, 1.januar(2004)), api.lovreferanse)
        assertEquals("Z999999", kilde.ident)
        assertEquals(listOf("journalpost-1", "journalpost-2"), kilde.journalpostId)
    }

    @Test
    fun `unntaksvilkår er en helt vanlig vilkårsvurdering`() {
        val vilkårsvurdering =
            Vilkårsvurdering.avSaksbehandler(
                vilkårskode = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                utfall = Utfall.IkkeOppfylt,
                saksbehandlerIdent = "Z999999",
                fritekstbegrunnelse = "Ingen AAP forut for foreldrepengeperioden.",
            )
        val vurdering =
            Medlemskapsvurdering.avSaksbehandler(
                fødselsnummer = "12345678901",
                skjæringstidspunkt = 1.februar,
                vilkårsvurdering = vilkårsvurdering,
            )

        val api = Vurderingsrespons.fra(vurdering).enesteVurdering()

        assertEquals(ApiVilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER, api.vilkårskode)
        assertEquals(ApiUtfall.IKKE_OPPFYLT, api.utfall)
    }

    @Test
    fun `infotrygdvurdering blir en medlemskapsvurdering uten enkeltvurderinger`() {
        val vurdering =
            Medlemskapsvurdering.fraInfotrygd(
                fødselsnummer = "12345678901",
                skjæringstidspunkt = 1.februar,
                erOk = true,
                vurdertTidspunkt = Instant.now(),
            )

        val krav = Vurderingsrespons.fra(vurdering).krav.single()

        assertInstanceOf(ApiMedlemskapsvurdering.OverførtFraInfotrygd::class.java, krav)
        assertEquals(ApiKravkode.MEDLEMSKAP, krav.kravkode)
        assertTrue(krav.medlemskapOk)
    }

    @Test
    fun `spleisvurdering blir en medlemskapsvurdering med spleis som kilde`() {
        val vurdering =
            Medlemskapsvurdering.overførtFraSpleis(
                id = MedlemskapsvurderingId.ny(),
                fødselsnummer = "12345678901",
                skjæringstidspunkt = 1.februar,
                vilkårsvurderinger =
                    listOf(
                        Vilkårsvurdering.overførtFraSpleis(
                            vilkårskode = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                            vurdertTidspunkt = Instant.now(),
                            utfall = Utfall.Oppfylt,
                            grunnlag = Medlemskapsgrunnlag.SelvstendigNæringsdrivende,
                            utledetFakta = UtledetFakta.Ingen,
                        ),
                    ),
            )

        val krav = Vurderingsrespons.fra(vurdering).krav.single()

        assertInstanceOf(ApiMedlemskapsvurdering.OverførtFraSpleis::class.java, krav)
        assertEquals(ApiKravkilde.OVERFORT_FRA_SPLEIS, krav.kravkilde)
    }

    @Test
    fun `selvstendig naeringsdrivende gir grunnlag uten arbeidsforhold`() {
        val vurdering =
            Medlemskapsvurdering.automatisk(
                medlemskapsprøvingId = MedlemskapsprøvingId.ny(),
                fødselsnummer = "12345678901",
                skjæringstidspunkt = 1.februar,
                grunnlag = Medlemskapsgrunnlag.SelvstendigNæringsdrivende,
            )

        val kilde = Vurderingsrespons.fra(vurdering).enesteVurdering().kilde as ApiVurderingskilde.Automatisk

        assertEquals(ApiVurderingsgrunnlag.SelvstendigNæringsdrivende(), kilde.grunnlag)
    }

    private fun automatiskArbeidstakervurdering(ansettelseperiode: Periode) =
        Medlemskapsvurdering.automatisk(
            medlemskapsprøvingId = MedlemskapsprøvingId.ny(),
            fødselsnummer = "12345678901",
            skjæringstidspunkt = 1.februar,
            grunnlag =
                Medlemskapsgrunnlag.Arbeidstaker(
                    listOf(Arbeidsforhold(orgnummer = "987654321", ansettelseperiode = ansettelseperiode, type = ORDINÆRT)),
                ),
        )
}

private fun ApiVilkårsvurderingerForPersonResponse.enesteVurdering() = (krav.single() as ApiMedlemskapsvurdering.VurdertISpVilkarproving).vurderinger.single()

private fun ApiVilkårsvurdering.automatiskGrunnlag() = (kilde as ApiVurderingskilde.Automatisk).grunnlag as ApiVurderingsgrunnlag.Arbeidsforhold
