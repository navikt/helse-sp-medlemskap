package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.februar
import no.nav.helse.januar
import no.nav.helse.sykepenger.medlemskap.application.MedlemskapService.BehandleGrunnlagResultat
import no.nav.helse.sykepenger.medlemskap.application.MedlemskapService.BehandleVentendeBehovResultat
import no.nav.helse.sykepenger.medlemskap.application.VurderMedlemskapResultat.HarVurdering
import no.nav.helse.sykepenger.medlemskap.application.VurderMedlemskapResultat.TrengerMedlemskapsopplysninger
import no.nav.helse.sykepenger.medlemskap.domain.Grunnlagsbehov
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Vurderingskilde
import no.nav.helse.sykepenger.medlemskap.infra.db.Database
import no.nav.helse.sykepenger.medlemskap.infra.db.DatabaseTest
import no.nav.helse.sykepenger.medlemskap.infra.db.Lagret
import no.nav.helse.til
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class MedlemskapServiceTest : DatabaseTest() {
    private val versjonAvKode = "nais-app-image"
    private val transaksjon = Database.transaksjonProvider
    private val oppslagsperiode = 1.februar til 28.februar

    private fun <T> service(block: (MedlemskapService) -> T) = transaksjon.transaksjon { block(MedlemskapService(it, versjonAvKode)) }

    @Test
    fun `uten eksisterende vurdering startes en prøving som venter på medlemskapsopplysninger`() {
        val resultat = service { it.vurderMedlemskap(FNR, 1.februar, oppslagsperiode) }

        val trenger = assertIs<TrengerMedlemskapsopplysninger>(resultat)
        val prøving = Lagret.prøvinger().single()
        assertEquals(TrengerMedlemskapsopplysninger(FNR, 1.februar, prøving.id, oppslagsperiode), trenger)
        assertEquals(Grunnlagsbehov.Medlemskap, prøving.uteståendeBehov)
        assertEquals(0, Lagret.antallVurderinger())
    }

    @Test
    fun `pågående prøving gjenbrukes med samme oppslagsperiode`() {
        val første = assertIs<TrengerMedlemskapsopplysninger>(service { it.vurderMedlemskap(FNR, 1.februar, oppslagsperiode) })

        val andre = service { it.vurderMedlemskap(FNR, 1.februar, 1.februar til 15.februar) }

        assertEquals(første, andre)
        assertEquals(1, Lagret.prøvinger().size)
    }

    @Test
    fun `eksisterende vurdering gjenbrukes`() {
        val eksisterende = fullførtPrøving(Medlemskapsstatus.Ja)

        val resultat = service { it.vurderMedlemskap(FNR, 1.februar, oppslagsperiode) }

        assertEquals(HarVurdering(FNR, 1.februar, eksisterende), resultat)
        assertEquals(1, Lagret.prøvinger().size)
    }

    @Test
    fun `vurdering overført fra Spleis gjenbrukes uten ny prøving`() {
        val overført = Medlemskapsvurdering.overførtFraSpleis(MedlemskapsvurderingId.ny(), FNR, 1.februar, Medlemskapsstatus.Ja, Instant.now())
        transaksjon.transaksjon { tx -> tx.medlemskapsvurderinger.lagre(overført) }

        assertEquals(HarVurdering(FNR, 1.februar, overført.id), service { it.vurderMedlemskap(FNR, 1.februar, oppslagsperiode) })
        assertTrue(Lagret.prøvinger().isEmpty())
    }

    @Test
    fun `svar fra medlemskapstjenesten gir automatisk vurdering`() {
        val trenger = assertIs<TrengerMedlemskapsopplysninger>(service { it.vurderMedlemskap(FNR, 1.februar, oppslagsperiode) })

        val resultat =
            service { it.behandleGrunnlagForAutomatiskMedlemskapsvurdering(Medlemskapsstatus.Nei, FNR, 1.februar, trenger.medlemskapsprøvingId) }

        val ny = assertIs<BehandleGrunnlagResultat.NyVurderingForetatt>(resultat)
        val vurdering = transaksjon.transaksjon { tx -> tx.medlemskapsvurderinger.finn(ny.medlemskapsvurderingId) }!!
        assertEquals(Medlemskapsstatus.Nei, vurdering.status)
        assertFalse(vurdering.erOk)
        assertEquals(Vurderingskilde.Automatisk(trenger.medlemskapsprøvingId, oppslagsperiode, versjonAvKode), vurdering.kilde)
        assertTrue(Lagret.prøvinger().single().erAvsluttet)
        assertTrue(Lagret.upubliserteMeldinger().isEmpty(), "Spleis får vurderingen i svaret på behovet")
    }

    @Test
    fun `svar for en prøving som allerede er fullført ignoreres`() {
        val trenger = assertIs<TrengerMedlemskapsopplysninger>(service { it.vurderMedlemskap(FNR, 1.februar, oppslagsperiode) })
        service { it.behandleGrunnlagForAutomatiskMedlemskapsvurdering(Medlemskapsstatus.Ja, FNR, 1.februar, trenger.medlemskapsprøvingId) }

        val resultat =
            service { it.behandleGrunnlagForAutomatiskMedlemskapsvurdering(Medlemskapsstatus.Nei, FNR, 1.februar, trenger.medlemskapsprøvingId) }

        assertEquals(BehandleGrunnlagResultat.AlleredeVurdert, resultat)
        assertEquals(1, Lagret.antallVurderinger())
    }

    @Test
    fun `svar for ukjent prøving avvises`() {
        service { it.vurderMedlemskap(FNR, 1.februar, oppslagsperiode) }

        val resultat =
            service { it.behandleGrunnlagForAutomatiskMedlemskapsvurdering(Medlemskapsstatus.Ja, FNR, 1.februar, MedlemskapsprøvingId.ny()) }

        assertEquals(BehandleGrunnlagResultat.IngenPrøvingFunnet, resultat)
        assertEquals(0, Lagret.antallVurderinger())
    }

    @Test
    fun `prøvinger på ulike skjæringstidspunkt er uavhengige`() {
        fullførtPrøving(Medlemskapsstatus.Ja)

        assertIs<TrengerMedlemskapsopplysninger>(service { it.vurderMedlemskap(FNR, 1.januar, 1.januar til 31.januar) })
    }

    @Test
    fun `ventende behov som venter, venter fortsatt til fristen`() {
        val frist = Instant.parse("2018-02-01T12:30:00Z")
        val venter =
            assertIs<VurderMedlemskapResultat.VenterPåForhåndsvurdering>(
                service { it.vurderMedlemskap(FNR, 1.februar, oppslagsperiode, MedlemskapService.Forhåndsvurderingsforespørsel("vedtaksperiode", "{}", frist)) },
            )

        val resultat = service { it.behandleVentendeBehov(venter.medlemskapsprøvingId, frist.minusSeconds(1)) }

        assertEquals(BehandleVentendeBehovResultat.VenterFortsatt, resultat)
        assertEquals(1, Lagret.ventendeBehov().size)
    }

    @Test
    fun `ventende behov som er borte, er ikke lenger aktuelt`() {
        val resultat = service { it.behandleVentendeBehov(MedlemskapsprøvingId.ny(), Instant.now()) }

        assertEquals(BehandleVentendeBehovResultat.IkkeLengerAktuelt, resultat)
    }

    private fun fullførtPrøving(status: Medlemskapsstatus): MedlemskapsvurderingId {
        val trenger = assertIs<TrengerMedlemskapsopplysninger>(service { it.vurderMedlemskap(FNR, 1.februar, oppslagsperiode) })
        val resultat = service { it.behandleGrunnlagForAutomatiskMedlemskapsvurdering(status, FNR, 1.februar, trenger.medlemskapsprøvingId) }
        return assertIs<BehandleGrunnlagResultat.NyVurderingForetatt>(resultat).medlemskapsvurderingId
    }

    private companion object {
        const val FNR = "12029240045"
    }
}
