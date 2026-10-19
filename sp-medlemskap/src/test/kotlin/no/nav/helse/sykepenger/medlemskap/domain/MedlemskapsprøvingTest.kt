package no.nav.helse.sykepenger.medlemskap.domain

import no.nav.helse.januar
import no.nav.helse.til
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
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

    private companion object {
        const val FNR = "12029240045"
    }
}
