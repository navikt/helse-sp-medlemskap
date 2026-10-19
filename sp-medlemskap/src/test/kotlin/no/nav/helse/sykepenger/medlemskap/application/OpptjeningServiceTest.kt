package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.*
import no.nav.helse.sykepenger.medlemskap.application.MedlemskapService.BehandleGrunnlagResultat
import no.nav.helse.sykepenger.medlemskap.application.VurderMedlemskapResultat.HarVurdering
import no.nav.helse.sykepenger.medlemskap.application.VurderMedlemskapResultat.TrengerArbeidsforhold
import no.nav.helse.sykepenger.medlemskap.domain.*
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidsforhold.Arbeidsforholdtype.ORDINÆRT
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertIs

internal class MedlemskapServiceTest {
    private val versjonAvKode = "nais-app-image"
    private val transaksjon = InMemoryTransaksjonProvider()
    private val vurderinger = transaksjon.medlemskapsvurderinger
    private val prøvinger = transaksjon.medlemskapsprøvinger
    private val service = MedlemskapService(transaksjon, versjonAvKode)

    @Test
    fun `arbeidstaker uten eksisterende vurdering starter en prøving som venter på arbeidsforhold`() {
        val resultat = service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker)

        assertEquals(TrengerArbeidsforhold(FØDSELSNUMMER, 1.februar), resultat)
        assertEquals(0, vurderinger.antallLagringer)

        val prøving = prøvinger.allePrøvinger.single()
        assertEquals(FØDSELSNUMMER, prøving.fødselsnummer)
        assertEquals(1.februar, prøving.skjæringstidspunkt)
        assertFalse(prøving.erAvsluttet)
        assertEquals(Grunnlagsbehov.Arbeidsforhold, prøving.uteståendeBehov)
    }

    @Test
    fun `selvstendig næringsdrivende vurderes umiddelbart`() {
        val resultat = service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.SelvstendigNæringsdrivende)

        val harVurdering = assertInstanceOf(HarVurdering::class.java, resultat)
        assertEquals(FØDSELSNUMMER, harVurdering.fødselsnummer)
        assertEquals(1.februar, harVurdering.skjæringstidspunkt)

        val vurdering = vurderinger.finn(harVurdering.medlemskapsvurderingId) as Medlemskapsvurdering.VurdertISpVilkårsprøving
        val vilkårsvurdering = vurdering.vilkårsvurderinger.single()
        assertEquals(Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER, vilkårsvurdering.vilkårskode)
        assertEquals(Utfall.Oppfylt, vilkårsvurdering.utfall)
        assertEquals(Medlemskapsgrunnlag.SelvstendigNæringsdrivende, (vilkårsvurdering.kilde as Vurderingskilde.Automatisk).grunnlag)
        assertEquals(versjonAvKode, vilkårsvurdering.kilde.versjonAvKildekode)
        assertTrue(prøvinger.allePrøvinger.single().erAvsluttet)
    }

    @Test
    fun `eksisterende vurdering gjenbrukes`() {
        val eksisterende = fullførtPrøving(1.februar)

        val resultat = service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker)

        assertEquals(HarVurdering(FØDSELSNUMMER, 1.februar, eksisterende), resultat)
        assertEquals(1, vurderinger.antallLagringer)
        assertEquals(1, prøvinger.allePrøvinger.size)
    }

    @Test
    fun `eksisterende vurdering med annen kategori erstattes ikke`() {
        val eksisterende = fullførtPrøving(1.februar)

        val resultat = service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.SelvstendigNæringsdrivende)

        val nyVurdering = assertInstanceOf(HarVurdering::class.java, resultat)
        assertNotEquals(eksisterende, nyVurdering.medlemskapsvurderingId)
        assertEquals(2, vurderinger.antallLagringer)
        assertEquals(2, prøvinger.allePrøvinger.size)
        assertEquals(Kategori.SelvstendigNæringsdrivende, vurderinger.finn(nyVurdering.medlemskapsvurderingId)!!.kategori)
    }

    @Test
    fun `eksisterende vurdering med samme kategori gjenbrukes`() {
        val eksisterende = service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.SelvstendigNæringsdrivende)
        val eksisterendeId = (eksisterende as HarVurdering).medlemskapsvurderingId

        val resultat = service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.SelvstendigNæringsdrivende)

        assertEquals(HarVurdering(FØDSELSNUMMER, 1.februar, eksisterendeId), resultat)
        assertEquals(1, prøvinger.allePrøvinger.size)
    }

    @Test
    fun `pågående prøving fører til nytt behov om arbeidsforhold, ikke ny prøving`() {
        service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker)

        val resultat = service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker)

        assertEquals(TrengerArbeidsforhold(FØDSELSNUMMER, 1.februar), resultat)
        assertEquals(1, prøvinger.allePrøvinger.size)
    }

    @Test
    fun `pågående arbeidstakerprøving blokkerer ikke selvstendig næringsdrivende`() {
        service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker)

        val resultat = service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.SelvstendigNæringsdrivende)

        val harVurdering = assertIs<HarVurdering>(resultat)
        assertEquals(2, prøvinger.allePrøvinger.size)
        assertEquals(Kategori.SelvstendigNæringsdrivende, vurderinger.finn(harVurdering.medlemskapsvurderingId)!!.kategori)
    }

    @Test
    fun `vurdering på et annet skjæringstidspunkt gjenbrukes ikke`() {
        fullførtPrøving(1.januar)

        val resultat = service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker)

        assertEquals(TrengerArbeidsforhold(FØDSELSNUMMER, 1.februar), resultat)
    }

    @Test
    fun `vurdering for en annen person gjenbrukes ikke`() {
        fullførtPrøving(1.februar, fødselsnummer = ET_ANNET_FØDSELSNUMMER)

        val resultat = service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker)

        assertEquals(TrengerArbeidsforhold(FØDSELSNUMMER, 1.februar), resultat)
    }

    @Test
    fun `grunnlag uten påbegynt prøving gir ingen prøving funnet`() {
        val resultat =
            service.behandleGrunnlagForAutomatiskArbeidstakerMedlemskapsvurdering(
                arbeidsforhold = listOf(arbeidsforhold(1.januar til 31.januar)),
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
            )

        assertEquals(BehandleGrunnlagResultat.IngenPrøvingFunnet, resultat)
        assertEquals(0, vurderinger.antallLagringer)
    }

    @Test
    fun `grunnlag fullfører påbegynt prøving og produserer vurderingen`() {
        service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker)
        val arbeidsforhold = listOf(arbeidsforhold(1.januar til 31.januar))

        val resultat =
            service.behandleGrunnlagForAutomatiskArbeidstakerMedlemskapsvurdering(
                arbeidsforhold = arbeidsforhold,
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
            )

        val nyVurdering = assertInstanceOf(BehandleGrunnlagResultat.NyVurderingForetatt::class.java, resultat)
        val prøving = prøvinger.allePrøvinger.single()
        assertEquals(Medlemskapsprøving.Tilstand.Fullført(nyVurdering.medlemskapsvurderingId), prøving.tilstand)

        val vurdering = vurderinger.finn(nyVurdering.medlemskapsvurderingId) as Medlemskapsvurdering.VurdertISpVilkårsprøving
        val vilkårsvurdering = vurdering.vilkårsvurderinger.single()
        val kilde = vilkårsvurdering.kilde as Vurderingskilde.Automatisk
        assertEquals(prøving.id, kilde.medlemskapsprøvingId)
        assertEquals(Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER, vilkårsvurdering.vilkårskode)
        assertEquals(Utfall.Oppfylt, vilkårsvurdering.utfall)
        assertEquals(arbeidsforhold, (kilde.grunnlag as Medlemskapsgrunnlag.Arbeidstaker).arbeidsforhold)
    }

    @Test
    fun `grunnlag med for kort medlemskap gir ikke oppfylt`() {
        service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker)

        service.behandleGrunnlagForAutomatiskArbeidstakerMedlemskapsvurdering(
            arbeidsforhold = listOf(arbeidsforhold(5.januar til 31.januar)),
            fødselsnummer = FØDSELSNUMMER,
            skjæringstidspunkt = 1.februar,
        )

        val vilkårsvurdering = (vurderinger.alleVurderinger.single() as Medlemskapsvurdering.VurdertISpVilkårsprøving).vilkårsvurderinger.single()
        assertEquals(Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER, vilkårsvurdering.vilkårskode)
        assertEquals(Utfall.IkkeOppfylt, vilkårsvurdering.utfall)
    }

    @Test
    fun `grunnlag uten arbeidsforhold fullfører prøvingen`() {
        service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker)

        val resultat =
            service.behandleGrunnlagForAutomatiskArbeidstakerMedlemskapsvurdering(
                arbeidsforhold = emptyList(),
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
            )

        assertIs<BehandleGrunnlagResultat.NyVurderingForetatt>(resultat)
        assertTrue(prøvinger.allePrøvinger.single().erAvsluttet)
        val vilkårsvurdering = (vurderinger.alleVurderinger.single() as Medlemskapsvurdering.VurdertISpVilkårsprøving).vilkårsvurderinger.single()
        assertEquals(Utfall.IkkeOppfylt, vilkårsvurdering.utfall)
    }

    @Test
    fun `grunnlag på allerede fullført prøving gjør ingenting`() {
        service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker)
        service.behandleGrunnlagForAutomatiskArbeidstakerMedlemskapsvurdering(
            arbeidsforhold = listOf(arbeidsforhold(1.januar til 31.januar)),
            fødselsnummer = FØDSELSNUMMER,
            skjæringstidspunkt = 1.februar,
        )
        val opprinneligVurdering = vurderinger.alleVurderinger.single()

        val resultat =
            service.behandleGrunnlagForAutomatiskArbeidstakerMedlemskapsvurdering(
                arbeidsforhold = listOf(arbeidsforhold(5.januar til 31.januar)),
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
            )

        assertEquals(BehandleGrunnlagResultat.AlleredeVurdert, resultat)
        assertSame(opprinneligVurdering, vurderinger.alleVurderinger.single())
    }

    @Test
    fun `grunnlag for et annet skjæringstidspunkt treffer ikke prøvingen`() {
        service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker)

        val resultat =
            service.behandleGrunnlagForAutomatiskArbeidstakerMedlemskapsvurdering(
                arbeidsforhold = listOf(arbeidsforhold(1.januar til 31.januar)),
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.mars,
            )

        assertEquals(BehandleGrunnlagResultat.IngenPrøvingFunnet, resultat)
        assertFalse(prøvinger.allePrøvinger.single().erAvsluttet)
        assertEquals(0, vurderinger.antallLagringer)
    }

    @Test
    fun `ny vurdering uten eksisterende vurdering gjør ingenting`() {
        val resultat = service.vurderMedlemskapPåNytt(FØDSELSNUMMER, 1.februar, SAKSBEHANDLER)

        assertEquals(MedlemskapService.VurderMedlemskapPåNyttResultat.IngenVurderingFunnet, resultat)
        assertTrue(prøvinger.allePrøvinger.isEmpty())
        assertTrue(transaksjon.outbox.upubliserteMeldinger.isEmpty())
    }

    @Test
    fun `ny vurdering av arbeidstaker starter ny prøving som venter på arbeidsforhold`() {
        fullførtPrøving(1.februar)

        val resultat = service.vurderMedlemskapPåNytt(FØDSELSNUMMER, 1.februar, SAKSBEHANDLER)

        val venter = assertIs<MedlemskapService.VurderMedlemskapPåNyttResultat.VenterPåGrunnlag>(resultat)
        val nyPrøving = prøvinger.allePrøvinger.last()
        assertEquals(2, prøvinger.allePrøvinger.size)
        assertEquals(nyPrøving.id, venter.medlemskapsprøvingId)
        assertEquals(Grunnlagsbehov.Arbeidsforhold, nyPrøving.uteståendeBehov)
        assertTrue(transaksjon.outbox.upubliserteMeldinger.isEmpty())
    }

    @Test
    fun `ny vurdering av selvstendig næringsdrivende vurderes umiddelbart og varsler endring`() {
        val eksisterende = service.vurderMedlemskap(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.SelvstendigNæringsdrivende) as HarVurdering

        val resultat = service.vurderMedlemskapPåNytt(FØDSELSNUMMER, 1.februar, SAKSBEHANDLER)

        val ny = assertIs<MedlemskapService.VurderMedlemskapPåNyttResultat.NyVurderingForetatt>(resultat)
        assertNotEquals(eksisterende.medlemskapsvurderingId, ny.medlemskapsvurderingId)
        assertEquals(ny.medlemskapsvurderingId, vurderinger.gjeldende(FØDSELSNUMMER, 1.februar)!!.id)
        assertEquals(Kategori.SelvstendigNæringsdrivende, vurderinger.finn(ny.medlemskapsvurderingId)!!.kategori)
        assertEquals(
            listOf(
                saksbehandlerhandling(ny.medlemskapsvurderingId),
                OutboxMelding.MedlemskapsvurderingEndret(1.februar, ny.medlemskapsvurderingId.value, manuellVurdering = false),
            ),
            transaksjon.outbox.upubliserteMeldinger.map { it.melding.utenTidspunkt() },
        )
    }

    @Test
    fun `ny vurdering mens en prøving pågår avvises`() {
        fullførtPrøving(1.februar)
        service.vurderMedlemskapPåNytt(FØDSELSNUMMER, 1.februar, SAKSBEHANDLER)

        val resultat = service.vurderMedlemskapPåNytt(FØDSELSNUMMER, 1.februar, SAKSBEHANDLER)

        assertEquals(MedlemskapService.VurderMedlemskapPåNyttResultat.PrøvingPågår, resultat)
        assertEquals(2, prøvinger.allePrøvinger.size)
        assertTrue(transaksjon.outbox.upubliserteMeldinger.isEmpty())
    }

    @Test
    fun `grunnlag til ny vurdering fullfører prøvingen og varsler endring`() {
        val gammel = fullførtPrøving(1.februar)
        val venter = service.vurderMedlemskapPåNytt(FØDSELSNUMMER, 1.februar, SAKSBEHANDLER) as MedlemskapService.VurderMedlemskapPåNyttResultat.VenterPåGrunnlag

        val resultat =
            service.behandleGrunnlagForNyArbeidstakerMedlemskapsvurdering(
                arbeidsforhold = listOf(arbeidsforhold(5.januar til 31.januar)),
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
                medlemskapsprøvingId = venter.medlemskapsprøvingId,
                saksbehandlerIdent = SAKSBEHANDLER,
            )

        val ny = assertIs<BehandleGrunnlagResultat.NyVurderingForetatt>(resultat)
        assertNotEquals(gammel, ny.medlemskapsvurderingId)
        assertTrue(prøvinger.allePrøvinger.all { it.erAvsluttet })
        val vurdering = vurderinger.gjeldende(FØDSELSNUMMER, 1.februar)!!
        assertEquals(ny.medlemskapsvurderingId, vurdering.id)
        assertFalse(vurdering.erOk)
        assertEquals(
            listOf(
                saksbehandlerhandling(ny.medlemskapsvurderingId),
                OutboxMelding.MedlemskapsvurderingEndret(1.februar, ny.medlemskapsvurderingId.value, manuellVurdering = false),
            ),
            transaksjon.outbox.upubliserteMeldinger.map { it.melding.utenTidspunkt() },
        )
    }

    @Test
    fun `grunnlag til ny vurdering for en annen prøving enn den siste ignoreres`() {
        fullførtPrøving(1.februar)
        service.vurderMedlemskapPåNytt(FØDSELSNUMMER, 1.februar, SAKSBEHANDLER)

        val resultat =
            service.behandleGrunnlagForNyArbeidstakerMedlemskapsvurdering(
                arbeidsforhold = listOf(arbeidsforhold(1.januar til 31.januar)),
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
                medlemskapsprøvingId = MedlemskapsprøvingId.ny(),
                saksbehandlerIdent = SAKSBEHANDLER,
            )

        assertEquals(BehandleGrunnlagResultat.IngenPrøvingFunnet, resultat)
        assertFalse(prøvinger.allePrøvinger.last().erAvsluttet)
        assertTrue(transaksjon.outbox.upubliserteMeldinger.isEmpty())
    }

    @Test
    fun `grunnlag til ny vurdering på allerede fullført prøving gjør ingenting`() {
        fullførtPrøving(1.februar)
        val venter = service.vurderMedlemskapPåNytt(FØDSELSNUMMER, 1.februar, SAKSBEHANDLER) as MedlemskapService.VurderMedlemskapPåNyttResultat.VenterPåGrunnlag
        val behandle = {
            service.behandleGrunnlagForNyArbeidstakerMedlemskapsvurdering(
                arbeidsforhold = listOf(arbeidsforhold(1.januar til 31.januar)),
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
                medlemskapsprøvingId = venter.medlemskapsprøvingId,
                saksbehandlerIdent = SAKSBEHANDLER,
            )
        }
        behandle()

        assertEquals(BehandleGrunnlagResultat.AlleredeVurdert, behandle())
        assertEquals(2, transaksjon.outbox.upubliserteMeldinger.size)
    }

    @Test
    fun `finner lagret medlemskapsvurdering`() {
        val medlemskapsvurderingId = fullførtPrøving(1.februar)

        assertEquals(medlemskapsvurderingId, service.finnMedlemskapsvurdering(medlemskapsvurderingId).id)
    }

    @Test
    fun `ukjent medlemskapsvurdering gir feil`() {
        val ukjentId = MedlemskapsvurderingId.ny()

        val feil = assertThrows<IllegalStateException> { service.finnMedlemskapsvurdering(ukjentId) }
        assertEquals("Fant ikke medlemskapsvurdering med id $ukjentId", feil.message)
    }

    private fun saksbehandlerhandling(medlemskapsvurderingId: MedlemskapsvurderingId) =
        OutboxMelding.Saksbehandlerhandling(
            skjæringstidspunkt = 1.februar,
            saksbehandlerIdent = SAKSBEHANDLER,
            handlingId = medlemskapsvurderingId.value,
            handlingType = HandlingType.NY_AUTOMATISK_MEDLEMSKAPSVURDERING,
            kreverTotrinnsvurdering = false,
            tidspunkt = Instant.EPOCH,
        )

    private fun OutboxMelding.utenTidspunkt() =
        when (this) {
            is OutboxMelding.Saksbehandlerhandling -> copy(tidspunkt = Instant.EPOCH)
            is OutboxMelding.MedlemskapsvurderingEndret -> this
        }

    private fun fullførtPrøving(
        skjæringstidspunkt: LocalDate,
        fødselsnummer: String = FØDSELSNUMMER,
    ): MedlemskapsvurderingId {
        val prøving = Medlemskapsprøving.start(fødselsnummer, skjæringstidspunkt, Arbeidssituasjon.Arbeidstaker).prøving
        val vurdering = prøving.motta(Medlemskapsgrunnlag.Arbeidstaker(listOf(arbeidsforhold(1.januar til 31.januar))))
        prøvinger.lagre(prøving)
        vurderinger.lagre(vurdering)
        return vurdering.id
    }

    private companion object {
        const val FØDSELSNUMMER = "12029240045"
        const val ET_ANNET_FØDSELSNUMMER = "12029240046"
        const val ORGNUMMER = "987654321"
        const val SAKSBEHANDLER = "Z999999"

        fun arbeidsforhold(
            ansettelseperiode: Periode,
            orgnummer: String = ORGNUMMER,
        ) = Arbeidsforhold(orgnummer = orgnummer, ansettelseperiode = ansettelseperiode, type = ORDINÆRT)
    }
}
