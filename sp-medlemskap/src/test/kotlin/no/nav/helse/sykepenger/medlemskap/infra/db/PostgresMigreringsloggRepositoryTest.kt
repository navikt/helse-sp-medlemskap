package no.nav.helse.sykepenger.medlemskap.infra.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class PostgresMigreringsloggRepositoryTest : DatabaseTest() {
    @Test
    fun `lagrer nedlastingsstatistikk og finner fødselsnummer med historikk`() {
        assertFalse(transaksjon { it.migreringslogg.finnesFor(FØDSELSNUMMER) })

        transaksjon {
            it.migreringslogg.lagre(
                fødselsnummer = FØDSELSNUMMER,
                antallVurderinger = 3,
                antallHoppetOver = 1,
            )
        }

        assertTrue(transaksjon { it.migreringslogg.finnesFor(FØDSELSNUMMER) })
        assertEquals(1, Database.antallRader("migreringslogg"))
        Database.dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    select fødselsnummer, tidspunkt, antall_vurderinger, antall_hoppet_over
                    from migreringslogg
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(FØDSELSNUMMER, resultSet.getString("fødselsnummer"))
                        assertNotNull(resultSet.getTimestamp("tidspunkt"))
                        assertEquals(3, resultSet.getInt("antall_vurderinger"))
                        assertEquals(1, resultSet.getInt("antall_hoppet_over"))
                        assertFalse(resultSet.next())
                    }
                }
        }
    }

    @Test
    fun `ruller tilbake historikk når transaksjonen feiler`() {
        assertThrows<RuntimeException> {
            transaksjon {
                it.migreringslogg.lagre(
                    fødselsnummer = FØDSELSNUMMER,
                    antallVurderinger = 1,
                    antallHoppetOver = 0,
                )
                throw RuntimeException("feil under lagring")
            }
        }

        assertFalse(transaksjon { it.migreringslogg.finnesFor(FØDSELSNUMMER) })
        assertEquals(0, Database.antallRader("migreringslogg"))
    }

    @Test
    fun `lagrer null vurderinger og finner personen ved neste oppslag`() {
        transaksjon {
            it.migreringslogg.lagre(FØDSELSNUMMER, antallVurderinger = 0, antallHoppetOver = 0)
        }

        assertTrue(transaksjon { it.migreringslogg.finnesFor(FØDSELSNUMMER) })
        assertEquals(1, Database.antallRader("migreringslogg"))
    }

    private companion object {
        const val FØDSELSNUMMER = "12029240045"
    }
}
