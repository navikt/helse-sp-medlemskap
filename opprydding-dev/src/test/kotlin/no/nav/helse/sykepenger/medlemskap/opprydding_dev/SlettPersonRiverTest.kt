package no.nav.helse.sykepenger.medlemskap.opprydding_dev

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

internal class SlettPersonRiverTest {
    private val rapid =
        TestRapid().apply {
            SlettPersonRiver(this, Database.dataSource)
        }

    @BeforeEach
    fun beforeEach() {
        Database.reset()
        rapid.reset()
    }

    companion object {
        @JvmStatic
        @AfterAll
        fun shutdown() {
            Database.shutdown()
        }
    }

    @Test
    fun `sletter all data for en person`() {
        val fødselsnummer = "01020312345"

        insertMedlemskapsproving(fødselsnummer = fødselsnummer)
        insertMedlemskapsvurdering(fødselsnummer = fødselsnummer)
        insertMigreringslogg(fødselsnummer = fødselsnummer)

        assertEquals(1, Database.countMedlemskapsproving())
        assertEquals(1, Database.countMedlemskapsvurdering())
        assertEquals(1, Database.countMigreringslogg())

        rapid.sendTestMessage(slettPersonMelding(fødselsnummer))

        assertEquals(0, Database.countMedlemskapsproving())
        assertEquals(0, Database.countMedlemskapsvurdering())
        assertEquals(0, Database.countMigreringslogg())
    }

    @Test
    fun `sletter ikke data for annen person`() {
        val fødselsnummer1 = "01020312345"
        val fødselsnummer2 = "02020312345"

        insertMedlemskapsproving(fødselsnummer = fødselsnummer1)
        insertMedlemskapsvurdering(fødselsnummer = fødselsnummer1)

        insertMedlemskapsproving(fødselsnummer = fødselsnummer2)
        insertMedlemskapsvurdering(fødselsnummer = fødselsnummer2)
        insertMigreringslogg(fødselsnummer = fødselsnummer1)
        insertMigreringslogg(fødselsnummer = fødselsnummer2)

        rapid.sendTestMessage(slettPersonMelding(fødselsnummer1))

        assertEquals(1, Database.countMedlemskapsproving())
        assertEquals(1, Database.countMedlemskapsvurdering())
        assertEquals(1, Database.countMigreringslogg())
    }

    @Test
    fun `publiserer person_slettet etter sletting`() {
        val fødselsnummer = "01020312345"
        insertMedlemskapsproving(fødselsnummer = fødselsnummer)

        rapid.sendTestMessage(slettPersonMelding(fødselsnummer))

        assertEquals(1, rapid.inspektør.size)
        val kvittering = rapid.inspektør.message(0)
        assertEquals("person_slettet", kvittering["@event_name"].asString())
        assertEquals(fødselsnummer, kvittering["fødselsnummer"].asString())
    }

    @Test
    fun `gjør ingenting for person uten data`() {
        rapid.sendTestMessage(slettPersonMelding("99999999999"))

        assertEquals(0, Database.countMedlemskapsproving())
    }

    private fun insertMedlemskapsproving(
        id: UUID = UUID.randomUUID(),
        fødselsnummer: String,
    ) {
        Database.dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                INSERT INTO medlemskapsproving
                    (id, fødselsnummer, skjæringstidspunkt, oppslag_fom, oppslag_tom, startet, tilstand, utestående_behov)
                VALUES (?, ?, ?, ?, ?, ?, 'VENTER_PÅ_GRUNNLAG', 'Medlemskap')
                """,
                ).use { stmt ->
                    stmt.setObject(1, id)
                    stmt.setString(2, fødselsnummer)
                    stmt.setObject(3, LocalDate.of(2026, 1, 1))
                    stmt.setObject(4, LocalDate.of(2026, 1, 1))
                    stmt.setObject(5, LocalDate.of(2026, 1, 31))
                    stmt.setObject(6, java.sql.Timestamp.from(Instant.now()))
                    stmt.executeUpdate()
                }
        }
    }

    private fun insertMedlemskapsvurdering(
        id: UUID = UUID.randomUUID(),
        fødselsnummer: String,
    ) {
        Database.dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                INSERT INTO medlemskapsvurdering
                    (id, fødselsnummer, skjæringstidspunkt, status, vurdert_tidspunkt, vurderingskilde, kilde)
                VALUES (?, ?, ?, 'JA', ?, 'OVERFOERT_FRA_SPLEIS', '{"type": "OVERFOERT_FRA_SPLEIS"}'::jsonb)
                """,
                ).use { stmt ->
                    stmt.setObject(1, id)
                    stmt.setString(2, fødselsnummer)
                    stmt.setObject(3, LocalDate.of(2026, 1, 1))
                    stmt.setObject(4, java.sql.Timestamp.from(Instant.now()))
                    stmt.executeUpdate()
                }
        }
    }

    private fun insertMigreringslogg(fødselsnummer: String) {
        Database.dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                INSERT INTO migreringslogg (fødselsnummer, antall_vurderinger, antall_hoppet_over)
                VALUES (?, 1, 0)
                """,
                ).use { stmt ->
                    stmt.setString(1, fødselsnummer)
                    stmt.executeUpdate()
                }
        }
    }

    private fun slettPersonMelding(fødselsnummer: String) =
        """
        {
            "@event_name": "slett_person",
            "@id": "${UUID.randomUUID()}",
            "fødselsnummer": "$fødselsnummer"
        }
        """.trimIndent()
}
