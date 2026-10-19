package no.nav.helse.sykepenger.medlemskap.domain

import no.nav.helse.januar
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class MedlemskapsvurderingTest {
    @Test
    fun `bare nei gir ikke ok medlemskap`() {
        assertTrue(Medlemskapsstatus.Ja.erOk)
        assertFalse(Medlemskapsstatus.Nei.erOk)
        // Som i Spleis: uavklart medlemskap stopper ikke utbetalingen, men gir varsel der.
        assertTrue(Medlemskapsstatus.Uavklart.erOk)
        assertTrue(Medlemskapsstatus.UavklartMedBrukerspørsmål.erOk)
    }

    @ParameterizedTest
    @EnumSource(Medlemskapsstatus::class, names = ["Ja", "Nei"])
    fun `saksbehandler kan konkludere`(status: Medlemskapsstatus) {
        val vurdering =
            Medlemskapsvurdering.avSaksbehandler(
                fødselsnummer = FNR,
                skjæringstidspunkt = 1.januar,
                status = status,
                saksbehandlerIdent = "A123456",
                fritekstbegrunnelse = "Begrunnelse",
                journalpostId = listOf("123"),
            )

        assertEquals(status, vurdering.status)
        assertEquals(Vurderingskilde.Saksbehandler("A123456", "Begrunnelse", listOf("123")), vurdering.kilde)
    }

    @ParameterizedTest
    @EnumSource(Medlemskapsstatus::class, names = ["Uavklart", "UavklartMedBrukerspørsmål"])
    fun `saksbehandler kan ikke la medlemskap være uavklart`(status: Medlemskapsstatus) {
        assertThrows<IllegalArgumentException> {
            Medlemskapsvurdering.avSaksbehandler(FNR, 1.januar, status, "A123456", "Begrunnelse")
        }
    }

    @Test
    fun `vurdering overført fra Infotrygd regnes som medlem`() {
        val id = MedlemskapsvurderingId.ny()
        val vurdering = Medlemskapsvurdering.overførtFraInfotrygd(id, FNR, 1.januar, Instant.now())

        assertEquals(id, vurdering.id)
        assertEquals(Medlemskapsstatus.Ja, vurdering.status)
        assertEquals(Vurderingskilde.OverførtFraInfotrygd, vurdering.kilde)
    }

    @Test
    fun `vurdering overført fra Spleis beholder status og id`() {
        val id = MedlemskapsvurderingId.ny()
        val vurdering = Medlemskapsvurdering.overførtFraSpleis(id, FNR, 1.januar, Medlemskapsstatus.Nei, Instant.now())

        assertEquals(id, vurdering.id)
        assertEquals(Medlemskapsstatus.Nei, vurdering.status)
        assertFalse(vurdering.erOk)
        assertEquals(Vurderingskilde.OverførtFraSpleis, vurdering.kilde)
    }

    private companion object {
        const val FNR = "12029240045"
    }
}
