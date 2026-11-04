package no.nav.helse.sykepenger.medlemskap.domain

import no.nav.helse.januar
import no.nav.helse.til
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class MedlemskapsprøvingTest {
    private val oppslagsperiode = 1.januar til 31.januar

    @Test
    fun `ny prøving venter på medlemskapsopplysninger`() {
        val prøving = Medlemskapsprøving.start(FNR, 1.januar, oppslagsperiode)

        assertEquals(Grunnlagsbehov.Medlemskap, prøving.uteståendeBehov)
        assertFalse(prøving.erAvsluttet)
        assertEquals(oppslagsperiode, prøving.oppslagsperiode)
    }

    @Test
    fun `svar fra medlemskapstjenesten gir automatisk vurdering og fullfører prøvingen`() {
        val prøving = Medlemskapsprøving.start(FNR, 1.januar, oppslagsperiode)

        val vurdering = prøving.motta(Medlemskapsstatus.Ja, versjonAvKode = "abc123")

        assertTrue(prøving.erAvsluttet)
        assertNull(prøving.uteståendeBehov)
        assertEquals(Medlemskapsprøving.Tilstand.Fullført(vurdering.id), prøving.tilstand)
        assertEquals(FNR, vurdering.fødselsnummer)
        assertEquals(1.januar, vurdering.skjæringstidspunkt)
        assertEquals(Medlemskapsstatus.Ja, vurdering.status)
        assertEquals(Vurderingskilde.Automatisk(prøving.id, oppslagsperiode, "abc123"), vurdering.kilde)
    }

    @Test
    fun `kan ikke motta svar to ganger`() {
        val prøving = Medlemskapsprøving.start(FNR, 1.januar, oppslagsperiode)
        prøving.motta(Medlemskapsstatus.Ja)

        assertThrows<IllegalStateException> { prøving.motta(Medlemskapsstatus.Nei) }
    }

    @Test
    fun `gjenoppretter fullført prøving fra lagring`() {
        val vurderingId = MedlemskapsvurderingId.ny()
        val prøving =
            Medlemskapsprøving.fraLagring(
                id = MedlemskapsprøvingId.ny(),
                fødselsnummer = FNR,
                skjæringstidspunkt = 1.januar,
                oppslagsperiode = oppslagsperiode,
                startet = Instant.now(),
                tilstand = Medlemskapsprøving.Tilstand.Fullført(vurderingId),
            )

        assertTrue(prøving.erAvsluttet)
        assertThrows<IllegalStateException> { prøving.motta(Medlemskapsstatus.Ja) }
    }

    @Test
    fun `forhåndsvurdering gir automatisk vurdering med søknaden som kilde`() {
        val prøving = Medlemskapsprøving.start(FNR, 1.januar, oppslagsperiode, Grunnlagsbehov.Forhåndsvurdering)
        val søknadId = UUID.randomUUID()
        val årsaker = listOf(Årsak("REGEL_3", "Forklaring"))

        assertTrue(prøving.venterPåForhåndsvurdering)
        assertThrows<IllegalStateException> { prøving.motta(Medlemskapsstatus.Ja) }

        val forhåndsvurdering = Forhåndsvurdering(ForhåndsvurderingId.ny(), søknadId, FNR, oppslagsperiode, Medlemskapsstatus.UavklartMedBrukerspørsmål, årsaker, Instant.now())
        val vurdering = prøving.motta(forhåndsvurdering, "abc123")

        assertTrue(prøving.erAvsluttet)
        assertEquals(Medlemskapsstatus.UavklartMedBrukerspørsmål, vurdering.status)
        assertEquals(Vurderingskilde.Automatisk(prøving.id, oppslagsperiode, "abc123", årsaker, søknadId, forhåndsvurdering.id), vurdering.kilde)
    }

    @Test
    fun `prøving som ikke fikk forhåndsvurdering i tide venter på medlemskapsopplysninger`() {
        val prøving = Medlemskapsprøving.start(FNR, 1.januar, oppslagsperiode, Grunnlagsbehov.Forhåndsvurdering)

        prøving.slåOppIMedlemskapstjenesten()

        assertFalse(prøving.venterPåForhåndsvurdering)
        assertEquals(Grunnlagsbehov.Medlemskap, prøving.uteståendeBehov)
        assertThrows<IllegalStateException> { prøving.slåOppIMedlemskapstjenesten() }
        assertThrows<IllegalStateException> {
            prøving.motta(Forhåndsvurdering(ForhåndsvurderingId.ny(), UUID.randomUUID(), FNR, null, Medlemskapsstatus.Ja, null, Instant.now()))
        }
        prøving.motta(Medlemskapsstatus.Ja)
        assertTrue(prøving.erAvsluttet)
    }

    private companion object {
        const val FNR = "12029240045"
    }
}
