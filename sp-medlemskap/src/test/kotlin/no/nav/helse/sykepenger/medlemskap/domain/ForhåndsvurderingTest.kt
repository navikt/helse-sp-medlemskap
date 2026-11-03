package no.nav.helse.sykepenger.medlemskap.domain

import no.nav.helse.Periode
import no.nav.helse.april
import no.nav.helse.februar
import no.nav.helse.januar
import no.nav.helse.mars
import no.nav.helse.sykepenger.medlemskap.domain.Forhåndsvurdering.Companion.gjeldendeFor
import no.nav.helse.til
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ForhåndsvurderingTest {
    @Test
    fun `gjelder skjæringstidspunkter i perioden, med endepunktene`() {
        val forhåndsvurdering = forhåndsvurdering(2.januar til 31.januar)

        assertFalse(forhåndsvurdering.gjelder(1.januar))
        assertTrue(forhåndsvurdering.gjelder(2.januar))
        assertTrue(forhåndsvurdering.gjelder(16.januar))
        assertTrue(forhåndsvurdering.gjelder(31.januar))
        assertFalse(forhåndsvurdering.gjelder(1.februar))
    }

    @Test
    fun `uten periode gjelder den ingenting`() {
        assertFalse(forhåndsvurdering(null).gjelder(16.januar))
    }

    @Test
    fun `den sist mottatte av de som gjelder, vinner`() {
        val første = forhåndsvurdering(1.januar til 15.februar, mottatt = Instant.parse("2018-01-20T12:00:00Z"))
        val siste = forhåndsvurdering(22.januar til 28.februar, mottatt = Instant.parse("2018-02-05T12:00:00Z"))
        val gjelderIkke = forhåndsvurdering(1.mars til 31.mars, mottatt = Instant.parse("2018-03-05T12:00:00Z"))
        val alle = listOf(gjelderIkke, siste, første)

        assertEquals(siste, alle.gjeldendeFor(25.januar))
        assertEquals(første, alle.gjeldendeFor(21.januar))
        assertNull(alle.gjeldendeFor(1.april))
    }

    private fun forhåndsvurdering(
        gyldigFor: Periode?,
        mottatt: Instant = Instant.now(),
    ) = Forhåndsvurdering(UUID.randomUUID(), "12029240045", gyldigFor, Medlemskapsstatus.Ja, null, mottatt)
}
