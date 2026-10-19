package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.februar
import no.nav.helse.januar
import no.nav.helse.sykepenger.medlemskap.application.MedlemskapService.BehandleGrunnlagResultat
import no.nav.helse.sykepenger.medlemskap.application.MedlemskapService.VurderMedlemskapPåNyttResultat
import no.nav.helse.sykepenger.medlemskap.application.VurderMedlemskapResultat.HarVurdering
import no.nav.helse.sykepenger.medlemskap.application.VurderMedlemskapResultat.TrengerMedlemskapsopplysninger
import no.nav.helse.sykepenger.medlemskap.domain.Grunnlagsbehov
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Vurderingskilde
import no.nav.helse.til
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class MedlemskapServiceTest {
    private val versjonAvKode = "nais-app-image"
    private val transaksjon = InMemoryTransaksjonProvider()
    private val vurderinger = transaksjon.medlemskapsvurderinger
    private val prøvinger = transaksjon.medlemskapsprøvinger
    private val outbox = transaksjon.outbox
    private val service = MedlemskapService(transaksjon, versjonAvKode)
    private val oppslagsperiode = 1.februar til 28.februar

    @Test
    fun `uten eksisterende vurdering startes en prøving som venter på medlemskapsopplysninger`() {
        val resultat = service.vurderMedlemskap(FNR, 1.februar, oppslagsperiode)

        val trenger = assertIs<TrengerMedlemskapsopplysninger>(resultat)
        val prøving = prøvinger.allePrøvinger.single()
        assertEquals(TrengerMedlemskapsopplysninger(FNR, 1.februar, prøving.id, oppslagsperiode), trenger)
        assertEquals(Grunnlagsbehov.Medlemskap, prøving.uteståendeBehov)
        assertEquals(0, vurderinger.antallLagringer)
    }

    @Test
    fun `pågående prøving gjenbrukes med samme oppslagsperiode`() {
        val første = assertIs<TrengerMedlemskapsopplysninger>(service.vurderMedlemskap(FNR, 1.februar, oppslagsperiode))

        val andre = service.vurderMedlemskap(FNR, 1.februar, 1.februar til 15.februar)

        assertEquals(første, andre)
        assertEquals(1, prøvinger.allePrøvinger.size)
    }

    @Test
    fun `eksisterende vurdering gjenbrukes`() {
        val eksisterende = fullførtPrøving(Medlemskapsstatus.Ja)

        val resultat = service.vurderMedlemskap(FNR, 1.februar, oppslagsperiode)

        assertEquals(HarVurdering(FNR, 1.februar, eksisterende), resultat)
        assertEquals(1, prøvinger.allePrøvinger.size)
    }

    @Test
    fun `vurdering overført fra Spleis gjenbrukes uten ny prøving`() {
        val overført = Medlemskapsvurdering.overførtFraSpleis(MedlemskapsvurderingId.ny(), FNR, 1.februar, Medlemskapsstatus.Ja, Instant.now())
        vurderinger.lagre(overført)

        assertEquals(HarVurdering(FNR, 1.februar, overført.id), service.vurderMedlemskap(FNR, 1.februar, oppslagsperiode))
        assertTrue(prøvinger.allePrøvinger.isEmpty())
    }

    @Test
    fun `svar fra medlemskapstjenesten gir automatisk vurdering`() {
        val trenger = assertIs<TrengerMedlemskapsopplysninger>(service.vurderMedlemskap(FNR, 1.februar, oppslagsperiode))

        val resultat =
            service.behandleGrunnlagForAutomatiskMedlemskapsvurdering(Medlemskapsstatus.Nei, FNR, 1.februar, trenger.medlemskapsprøvingId)

        val ny = assertIs<BehandleGrunnlagResultat.NyVurderingForetatt>(resultat)
        val vurdering = vurderinger.finn(ny.medlemskapsvurderingId)!!
        assertEquals(Medlemskapsstatus.Nei, vurdering.status)
        assertFalse(vurdering.erOk)
        assertEquals(Vurderingskilde.Automatisk(trenger.medlemskapsprøvingId, oppslagsperiode, versjonAvKode), vurdering.kilde)
        assertTrue(prøvinger.allePrøvinger.single().erAvsluttet)
        assertTrue(outbox.upubliserteMeldinger.isEmpty(), "Spleis får vurderingen i svaret på behovet")
    }

    @Test
    fun `svar for en prøving som allerede er fullført ignoreres`() {
        val trenger = assertIs<TrengerMedlemskapsopplysninger>(service.vurderMedlemskap(FNR, 1.februar, oppslagsperiode))
        service.behandleGrunnlagForAutomatiskMedlemskapsvurdering(Medlemskapsstatus.Ja, FNR, 1.februar, trenger.medlemskapsprøvingId)

        val resultat =
            service.behandleGrunnlagForAutomatiskMedlemskapsvurdering(Medlemskapsstatus.Nei, FNR, 1.februar, trenger.medlemskapsprøvingId)

        assertEquals(BehandleGrunnlagResultat.AlleredeVurdert, resultat)
        assertEquals(1, vurderinger.antallLagringer)
    }

    @Test
    fun `svar for ukjent prøving avvises`() {
        service.vurderMedlemskap(FNR, 1.februar, oppslagsperiode)

        val resultat =
            service.behandleGrunnlagForAutomatiskMedlemskapsvurdering(Medlemskapsstatus.Ja, FNR, 1.februar, MedlemskapsprøvingId.ny())

        assertEquals(BehandleGrunnlagResultat.IngenPrøvingFunnet, resultat)
        assertEquals(0, vurderinger.antallLagringer)
    }

    @Test
    fun `ny vurdering krever en eksisterende vurdering`() {
        assertEquals(VurderMedlemskapPåNyttResultat.IngenVurderingFunnet, service.vurderMedlemskapPåNytt(FNR, 1.februar))
    }

    @Test
    fun `ny vurdering kan ikke startes mens en prøving pågår`() {
        fullførtPrøving(Medlemskapsstatus.Ja)
        service.vurderMedlemskapPåNytt(FNR, 1.februar)

        assertEquals(VurderMedlemskapPåNyttResultat.PrøvingPågår, service.vurderMedlemskapPåNytt(FNR, 1.februar))
    }

    @Test
    fun `ny vurdering spør om samme periode som forrige prøving`() {
        fullførtPrøving(Medlemskapsstatus.Ja)

        val resultat = assertIs<VurderMedlemskapPåNyttResultat.VenterPåGrunnlag>(service.vurderMedlemskapPåNytt(FNR, 1.februar))

        assertEquals(oppslagsperiode, resultat.oppslagsperiode)
        assertEquals(2, prøvinger.allePrøvinger.size)
    }

    @Test
    fun `ny vurdering av manuell vurdering spør om skjæringstidspunktet`() {
        vurderinger.lagre(Medlemskapsvurdering.avSaksbehandler(FNR, 1.februar, Medlemskapsstatus.Nei, "A123456", "Begrunnelse"))

        val resultat = assertIs<VurderMedlemskapPåNyttResultat.VenterPåGrunnlag>(service.vurderMedlemskapPåNytt(FNR, 1.februar))

        assertEquals(1.februar til 1.februar, resultat.oppslagsperiode)
    }

    @Test
    fun `svar på ny vurdering lagrer vurderingen og melder fra via outbox`() {
        val forrige = fullførtPrøving(Medlemskapsstatus.Nei)
        val venter = assertIs<VurderMedlemskapPåNyttResultat.VenterPåGrunnlag>(service.vurderMedlemskapPåNytt(FNR, 1.februar))

        val resultat =
            service.behandleGrunnlagForNyMedlemskapsvurdering(
                svar = Medlemskapsstatus.Ja,
                fødselsnummer = FNR,
                skjæringstidspunkt = 1.februar,
                medlemskapsprøvingId = venter.medlemskapsprøvingId,
                saksbehandlerIdent = "A123456",
            )

        val ny = assertIs<BehandleGrunnlagResultat.NyVurderingForetatt>(resultat)
        assertNotEquals(forrige, ny.medlemskapsvurderingId)
        assertEquals(ny.medlemskapsvurderingId, vurderinger.gjeldende(FNR, 1.februar)!!.id)

        val meldinger = outbox.upubliserteMeldinger.map { it.melding }
        val handling = meldinger.filterIsInstance<OutboxMelding.Saksbehandlerhandling>().single()
        assertEquals(HandlingType.NY_AUTOMATISK_MEDLEMSKAPSVURDERING, handling.handlingType)
        assertEquals("A123456", handling.saksbehandlerIdent)
        assertEquals(ny.medlemskapsvurderingId.value, handling.handlingId)
        assertFalse(handling.kreverTotrinnsvurdering)
        val endret = meldinger.filterIsInstance<OutboxMelding.MedlemskapsvurderingEndret>().single()
        assertEquals(OutboxMelding.MedlemskapsvurderingEndret(1.februar, ny.medlemskapsvurderingId.value, manuellVurdering = false), endret)
    }

    @Test
    fun `prøvinger på ulike skjæringstidspunkt er uavhengige`() {
        fullførtPrøving(Medlemskapsstatus.Ja)

        assertIs<TrengerMedlemskapsopplysninger>(service.vurderMedlemskap(FNR, 1.januar, 1.januar til 31.januar))
    }

    private fun fullførtPrøving(status: Medlemskapsstatus): MedlemskapsvurderingId {
        val trenger = assertIs<TrengerMedlemskapsopplysninger>(service.vurderMedlemskap(FNR, 1.februar, oppslagsperiode))
        val resultat = service.behandleGrunnlagForAutomatiskMedlemskapsvurdering(status, FNR, 1.februar, trenger.medlemskapsprøvingId)
        return assertIs<BehandleGrunnlagResultat.NyVurderingForetatt>(resultat).medlemskapsvurderingId
    }

    private companion object {
        const val FNR = "12029240045"
    }
}
