package no.nav.helse.sykepenger.medlemskap.infra.db

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import no.nav.helse.sykepenger.medlemskap.application.HandlingType
import no.nav.helse.sykepenger.medlemskap.application.OutboxKonvolutt
import no.nav.helse.sykepenger.medlemskap.application.OutboxKonvoluttId
import no.nav.helse.sykepenger.medlemskap.application.OutboxMelding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal class PostgresOutboxTest : DatabaseTest() {
    private fun nyMelding(manuellVurdering: Boolean = true) =
        OutboxMelding.MedlemskapsvurderingEndret(
            skjæringstidspunkt = LocalDate.of(2024, 1, 1),
            medlemskapsvurderingId = UUID.randomUUID(),
            manuellVurdering = manuellVurdering,
        )

    private fun nySaksbehandlerhandling() =
        OutboxMelding.Saksbehandlerhandling(
            skjæringstidspunkt = LocalDate.of(2024, 1, 1),
            saksbehandlerIdent = "Z123456",
            handlingId = UUID.randomUUID(),
            handlingType = HandlingType.MANUELT_VURDERT_MEDLEMSKAP,
            kreverTotrinnsvurdering = true,
            tidspunkt = Instant.parse("2024-01-01T12:00:00Z"),
        )

    private fun assertSammeInnhold(
        forventet: OutboxKonvolutt,
        faktisk: OutboxKonvolutt,
    ) {
        assertEquals(forventet.id, faktisk.id)
        assertEquals(forventet.identitetsnummer, faktisk.identitetsnummer)
        assertEquals(forventet.melding, faktisk.melding)
    }

    @Test
    fun `lagt til melding kommer tilbake som upublisert`() {
        val melding = OutboxKonvolutt(id = OutboxKonvoluttId.ny(), melding = nyMelding(), identitetsnummer = Identitetsnummer("12029240045"))

        transaksjon { it.outbox.leggTil(melding) }

        val upubliserte = transaksjon { it.outbox.hentUpubliserte() }
        assertSammeInnhold(melding, upubliserte.single())
    }

    @Test
    fun `publiserte meldinger dukker ikke opp igjen`() {
        val melding = OutboxKonvolutt(id = OutboxKonvoluttId.ny(), melding = nyMelding(), identitetsnummer = Identitetsnummer("12029240045"))
        transaksjon { it.outbox.leggTil(melding) }

        transaksjon { it.outbox.markerSomSendt(melding.id) }

        assertTrue(transaksjon { it.outbox.hentUpubliserte() }.isEmpty())
    }

    @Test
    fun `henter kun de eldste meldingene når maksAntall er satt`() {
        val meldinger = (1..3).map { OutboxKonvolutt(id = OutboxKonvoluttId.ny(), melding = nyMelding(manuellVurdering = it % 2 == 0), identitetsnummer = Identitetsnummer("12029240045")) }
        transaksjon { kontekst -> meldinger.forEach { kontekst.outbox.leggTil(it) } }

        val upubliserte = transaksjon { it.outbox.hentUpubliserte(maksAntall = 2) }

        assertEquals(2, upubliserte.size)
        meldinger.take(2).zip(upubliserte).forEach { (forventet, faktisk) -> assertSammeInnhold(forventet, faktisk) }
    }

    @Test
    fun `bevarer innsettingsrekkefølge for meldinger i samme transaksjon`() {
        val identitetsnummer = Identitetsnummer("12029240045")
        val saksbehandlerhandling = OutboxKonvolutt.ny(nySaksbehandlerhandling(), identitetsnummer)
        val medlemskapsvurderingEndret = OutboxKonvolutt.ny(nyMelding(), identitetsnummer)

        transaksjon { kontekst ->
            kontekst.outbox.leggTil(saksbehandlerhandling)
            kontekst.outbox.leggTil(medlemskapsvurderingEndret)
        }

        val hentedeMeldinger = transaksjon { it.outbox.hentUpubliserte() }

        assertEquals(
            listOf(saksbehandlerhandling.melding, medlemskapsvurderingEndret.melding),
            hentedeMeldinger.map { it.melding },
        )
    }

    @Test
    fun `hentUpubliserte gir tom liste når outboxen er tom`() {
        assertTrue(transaksjon { it.outbox.hentUpubliserte() }.isEmpty())
    }

    @Test
    fun `meldingen lagres med typediskriminatoren MEDLEMSKAPSVURDERING_ENDRET`() {
        val melding = OutboxKonvolutt(id = OutboxKonvoluttId.ny(), melding = nyMelding(), identitetsnummer = Identitetsnummer("12029240045"))

        transaksjon { it.outbox.leggTil(melding) }

        assertEquals(listOf("MEDLEMSKAPSVURDERING_ENDRET"), Database.meldingstyperIOutbox())
    }

    @Test
    fun `meldingen lagres med typediskriminatoren SAKSBEHANDLERHANDLING og leses tilbake`() {
        val melding = OutboxKonvolutt(id = OutboxKonvoluttId.ny(), melding = nySaksbehandlerhandling(), identitetsnummer = Identitetsnummer("12029240045"))

        transaksjon { it.outbox.leggTil(melding) }

        assertEquals(listOf("SAKSBEHANDLERHANDLING"), Database.meldingstyperIOutbox())
        assertSammeInnhold(melding, transaksjon { it.outbox.hentUpubliserte().single() })
    }
}
