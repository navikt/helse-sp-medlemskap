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
        val medlemskapsvurderingId = insertMedlemskapsvurdering(fødselsnummer = fødselsnummer)
        insertVilkarsvurdering(medlemskapsvurderingId = medlemskapsvurderingId, avgjørende = true)
        insertMigreringslogg(fødselsnummer = fødselsnummer)

        assertEquals(1, Database.countMedlemskapsproving())
        assertEquals(1, Database.countMedlemskapsvurdering())
        assertEquals(1, Database.countVilkarsvurdering())
        assertEquals(1, Database.countMedlemskapsvurderingVilkarsvurdering())
        assertEquals(1, Database.countMigreringslogg())

        rapid.sendTestMessage(slettPersonMelding(fødselsnummer))

        assertEquals(0, Database.countMedlemskapsproving())
        assertEquals(0, Database.countMedlemskapsvurdering())
        assertEquals(0, Database.countVilkarsvurdering())
        assertEquals(0, Database.countMedlemskapsvurderingVilkarsvurdering())
        assertEquals(0, Database.countMigreringslogg())
    }

    @Test
    fun `sletter ikke data for annen person`() {
        val fødselsnummer1 = "01020312345"
        val fødselsnummer2 = "02020312345"

        insertMedlemskapsproving(fødselsnummer = fødselsnummer1)
        val medlemskapsvurderingId1 = insertMedlemskapsvurdering(fødselsnummer = fødselsnummer1)
        insertVilkarsvurdering(medlemskapsvurderingId = medlemskapsvurderingId1)

        insertMedlemskapsproving(fødselsnummer = fødselsnummer2)
        val medlemskapsvurderingId2 = insertMedlemskapsvurdering(fødselsnummer = fødselsnummer2)
        insertVilkarsvurdering(medlemskapsvurderingId = medlemskapsvurderingId2)
        insertMigreringslogg(fødselsnummer = fødselsnummer1)
        insertMigreringslogg(fødselsnummer = fødselsnummer2)

        rapid.sendTestMessage(slettPersonMelding(fødselsnummer1))

        assertEquals(1, Database.countMedlemskapsproving())
        assertEquals(1, Database.countMedlemskapsvurdering())
        assertEquals(1, Database.countVilkarsvurdering())
        assertEquals(1, Database.countMedlemskapsvurderingVilkarsvurdering())
        assertEquals(1, Database.countMigreringslogg())
    }

    @Test
    fun `beholder vilkårsvurderinger som deles med annen person`() {
        val fødselsnummer1 = "01020312345"
        val fødselsnummer2 = "02020312345"
        val medlemskapsvurderingId1 = insertMedlemskapsvurdering(fødselsnummer = fødselsnummer1)
        val medlemskapsvurderingId2 = insertMedlemskapsvurdering(fødselsnummer = fødselsnummer2)
        val vilkarsvurderingId = UUID.randomUUID()
        insertVilkarsvurdering(id = vilkarsvurderingId, medlemskapsvurderingId = medlemskapsvurderingId1, avgjørende = true)
        kobleVilkarsvurdering(vilkarsvurderingId, medlemskapsvurderingId2, avgjørende = true)

        rapid.sendTestMessage(slettPersonMelding(fødselsnummer1))

        assertEquals(1, Database.countMedlemskapsvurdering())
        assertEquals(1, Database.countVilkarsvurdering())
        assertEquals(1, Database.countMedlemskapsvurderingVilkarsvurdering())

        rapid.sendTestMessage(slettPersonMelding(fødselsnummer2))

        assertEquals(0, Database.countMedlemskapsvurdering())
        assertEquals(0, Database.countVilkarsvurdering())
        assertEquals(0, Database.countMedlemskapsvurderingVilkarsvurdering())
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
                    (id, fødselsnummer, skjæringstidspunkt, kategori, startet, tilstand)
                VALUES (?, ?, ?, 'ARBEIDSTAKER', ?, 'STARTET')
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

    private fun insertMedlemskapsvurdering(
        id: UUID = UUID.randomUUID(),
        fødselsnummer: String,
    ): UUID {
        Database.dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                INSERT INTO medlemskapsvurdering
                    (id, fødselsnummer, skjæringstidspunkt, kategori, vurderingskilde, medlemskap_ok, vurdert_tidspunkt)
                VALUES (?, ?, ?, 'ARBEIDSTAKER', 'VURDERT_I_SP_medlemskap', true, ?)
                """,
                ).use { stmt ->
                    stmt.setObject(1, id)
                    stmt.setString(2, fødselsnummer)
                    stmt.setObject(3, LocalDate.of(2026, 1, 1))
                    stmt.setObject(4, java.sql.Timestamp.from(Instant.now()))
                    stmt.executeUpdate()
                }
        }
        return id
    }

    private fun insertVilkarsvurdering(
        id: UUID = UUID.randomUUID(),
        medlemskapsvurderingId: UUID,
        avgjørende: Boolean = false,
    ) {
        Database.dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                INSERT INTO vilkarsvurdering
                    (id, vilkårskode, utfall, vurdert_tidspunkt, vurderingskilde, kilde, lovreferanse)
                VALUES (?, 'MEDLEMSKAP_ARBEID_MINST_4_UKER', 'OPPFYLT', ?, 'VURDERT_I_SP_medlemskap', '{}'::jsonb, '{}'::jsonb)
                """,
                ).use { stmt ->
                    stmt.setObject(1, id)
                    stmt.setObject(2, java.sql.Timestamp.from(Instant.now()))
                    stmt.executeUpdate()
                }
        }
        kobleVilkarsvurdering(id, medlemskapsvurderingId, avgjørende)
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

    private fun kobleVilkarsvurdering(
        vilkarsvurderingId: UUID,
        medlemskapsvurderingId: UUID,
        avgjørende: Boolean,
    ) {
        Database.dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                INSERT INTO medlemskapsvurdering_vilkarsvurdering
                    (medlemskapsvurdering_id, vilkarsvurdering_id)
                VALUES (?, ?)
                """,
                ).use { stmt ->
                    stmt.setObject(1, medlemskapsvurderingId)
                    stmt.setObject(2, vilkarsvurderingId)
                    stmt.executeUpdate()
                }
            if (avgjørende) {
                conn
                    .prepareStatement(
                        "UPDATE medlemskapsvurdering SET avgjørende_vilkårsvurdering = ? WHERE id = ?",
                    ).use { stmt ->
                        stmt.setObject(1, vilkarsvurderingId)
                        stmt.setObject(2, medlemskapsvurderingId)
                        stmt.executeUpdate()
                    }
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
