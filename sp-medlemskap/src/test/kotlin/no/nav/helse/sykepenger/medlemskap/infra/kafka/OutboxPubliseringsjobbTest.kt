package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import com.github.navikt.tbd_libs.rapids_and_rivers_api.FailedMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.OutgoingMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import com.github.navikt.tbd_libs.rapids_and_rivers_api.SentMessage
import no.nav.helse.sykepenger.medlemskap.application.HandlingType
import no.nav.helse.sykepenger.medlemskap.application.InMemoryTransaksjonProvider
import no.nav.helse.sykepenger.medlemskap.application.OutboxKonvolutt
import no.nav.helse.sykepenger.medlemskap.application.OutboxKonvoluttId
import no.nav.helse.sykepenger.medlemskap.application.OutboxMelding
import no.nav.helse.sykepenger.medlemskap.leaderelection.LeaderElection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.*

internal class OutboxPubliseringsjobbTest {
    private val transaksjon = InMemoryTransaksjonProvider()

    private fun nyMelding(id: UUID = UUID.randomUUID()) =
        OutboxMelding.MedlemskapsvurderingEndret(
            skjæringstidspunkt = LocalDate.of(2024, 1, 1),
            medlemskapsvurderingId = id,
            manuellVurdering = true,
        )

    private fun nyJobb(
        rapid: RapidsConnection,
        isLeader: Boolean = true,
    ) = OutboxPubliseringsjobb(rapid, transaksjon, LeaderElection { isLeader })

    @Test
    fun `plukker opp upubliserte meldinger, publiserer dem og markerer dem som publisert`() {
        val rapid = TestRapid()
        val jobb = nyJobb(rapid)
        val medlemskapsvurderingId = UUID.randomUUID()
        val melding = OutboxKonvolutt(id = OutboxKonvoluttId.ny(), melding = nyMelding(medlemskapsvurderingId), identitetsnummer = Identitetsnummer("12029240045"))
        transaksjon.outbox.leggTil(melding)

        jobb.kjørEnRunde()

        assertEquals(1, rapid.inspektør.size)
        val publisert = rapid.inspektør.message(0)
        assertEquals("endret_medlemskapsvurdering", publisert.path("@event_name").asString())
        assertEquals(medlemskapsvurderingId.toString(), publisert.path("medlemskapsvurderingId").asString())
        assertEquals(melding.identitetsnummer.value, rapid.inspektør.key(0))
        assertTrue(transaksjon.outbox.hentUpubliserte().isEmpty())
    }

    @Test
    fun `flere meldinger publiseres i rekkefølge`() {
        val rapid = TestRapid()
        val jobb = nyJobb(rapid)
        val meldinger = (1..3).map { OutboxKonvolutt(id = OutboxKonvoluttId.ny(), melding = nyMelding(), identitetsnummer = Identitetsnummer("12029240045")) }
        meldinger.forEach { transaksjon.outbox.leggTil(it) }

        jobb.kjørEnRunde()

        assertEquals(3, rapid.inspektør.size)
        assertTrue(transaksjon.outbox.hentUpubliserte().isEmpty())
    }

    @Test
    fun `publiserer saksbehandlerhandling før endret medlemskapsvurdering`() {
        val rapid = TestRapid()
        val jobb = nyJobb(rapid)
        val identitetsnummer = Identitetsnummer("12029240045")
        val medlemskapsvurderingId = UUID.randomUUID()
        val meldinger =
            listOf(
                OutboxKonvolutt(
                    id = OutboxKonvoluttId.ny(),
                    melding =
                        OutboxMelding.Saksbehandlerhandling(
                            skjæringstidspunkt = LocalDate.of(2024, 1, 1),
                            saksbehandlerIdent = "Z999999",
                            handlingId = medlemskapsvurderingId,
                            handlingType = HandlingType.MANUELT_VURDERT_MEDLEMSKAP,
                            kreverTotrinnsvurdering = true,
                            tidspunkt = Instant.parse("2024-01-01T12:00:00Z"),
                        ),
                    identitetsnummer = identitetsnummer,
                ),
                OutboxKonvolutt(
                    id = OutboxKonvoluttId.ny(),
                    melding = nyMelding(medlemskapsvurderingId),
                    identitetsnummer = identitetsnummer,
                ),
            )
        meldinger.forEach(transaksjon.outbox::leggTil)

        jobb.kjørEnRunde()

        assertEquals(
            listOf("saksbehandlerhandling", "endret_medlemskapsvurdering"),
            (0 until rapid.inspektør.size).map {
                rapid.inspektør
                    .message(it)
                    .path("@event_name")
                    .asString()
            },
        )
    }

    @Test
    fun `ingen upubliserte meldinger gir ingen publisering`() {
        val rapid = TestRapid()
        val jobb = nyJobb(rapid)

        jobb.kjørEnRunde()

        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `publiserer ikke når poden ikke er leader`() {
        val rapid = TestRapid()
        val jobb = nyJobb(rapid, isLeader = false)
        val melding = OutboxKonvolutt(id = OutboxKonvoluttId.ny(), melding = nyMelding(), identitetsnummer = Identitetsnummer("12029240045"))
        transaksjon.outbox.leggTil(melding)

        jobb.kjørEnRundeHvisLeader()

        assertEquals(0, rapid.inspektør.size)
        assertEquals(listOf(melding), transaksjon.outbox.hentUpubliserte())
    }

    @Test
    fun `publiserer når poden er leader`() {
        val rapid = TestRapid()
        val jobb = nyJobb(rapid)
        transaksjon.outbox.leggTil(
            OutboxKonvolutt(id = OutboxKonvoluttId.ny(), melding = nyMelding(), identitetsnummer = Identitetsnummer("12029240045")),
        )

        jobb.kjørEnRundeHvisLeader()

        assertEquals(1, rapid.inspektør.size)
        assertTrue(transaksjon.outbox.hentUpubliserte().isEmpty())
    }

    @Test
    fun `feil under publisering markerer ikke meldingen som publisert, og jobben kastes ikke videre`() {
        val sviktendeRapid = SvikterVedPubliseringRapid()
        val jobb = nyJobb(sviktendeRapid)
        val melding = OutboxKonvolutt(id = OutboxKonvoluttId.ny(), melding = nyMelding(), identitetsnummer = Identitetsnummer("12029240045"))
        transaksjon.outbox.leggTil(melding)

        jobb.kjørEnRunde()

        assertEquals(listOf(melding), transaksjon.outbox.hentUpubliserte())
    }

    @Test
    fun `feil ved publisering av melding nummer to lar senere meldinger upublisert`() {
        val rapid = SvikterVedPubliseringNrRapid(feilerPå = 2)
        val jobb = nyJobb(rapid)
        val meldinger = (1..3).map { OutboxKonvolutt(id = OutboxKonvoluttId.ny(), melding = nyMelding(), identitetsnummer = Identitetsnummer("12029240045")) }
        meldinger.forEach(transaksjon.outbox::leggTil)

        jobb.kjørEnRunde()

        assertEquals(meldinger.drop(1), transaksjon.outbox.hentUpubliserte())
    }

    private class SvikterVedPubliseringRapid : RapidsConnection() {
        override fun publish(message: String) {
            publish(listOf(OutgoingMessage(message)))
        }

        override fun publish(
            key: String,
            message: String,
        ) {
            publish(listOf(OutgoingMessage(message, key)))
        }

        override fun publish(messages: List<OutgoingMessage>): Pair<List<SentMessage>, List<FailedMessage>> =
            emptyList<SentMessage>() to
                messages.mapIndexed { index, message -> FailedMessage(index, message, RuntimeException("Kafka er nede")) }

        override fun rapidName() = "svikter-rapid"

        override fun start() = Unit

        override fun stop() = Unit
    }

    private class SvikterVedPubliseringNrRapid(
        private val feilerPå: Int,
    ) : RapidsConnection() {
        private var publiseringer = 0

        override fun publish(message: String) {
            publish(listOf(OutgoingMessage(message)))
        }

        override fun publish(
            key: String,
            message: String,
        ) {
            publish(listOf(OutgoingMessage(message, key)))
        }

        override fun publish(messages: List<OutgoingMessage>): Pair<List<SentMessage>, List<FailedMessage>> {
            publiseringer++
            val feiledeMeldinger =
                if (publiseringer == feilerPå) {
                    messages.mapIndexed { index, message -> FailedMessage(index, message, RuntimeException("Kafka er nede")) }
                } else {
                    emptyList()
                }
            return emptyList<SentMessage>() to feiledeMeldinger
        }

        override fun rapidName() = "svikter-rapid"

        override fun start() = Unit

        override fun stop() = Unit
    }
}
