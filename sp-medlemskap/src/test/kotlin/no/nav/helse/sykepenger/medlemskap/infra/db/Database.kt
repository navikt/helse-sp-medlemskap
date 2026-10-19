package no.nav.helse.sykepenger.medlemskap.infra.db

import no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures.TestDatabase
import java.sql.ResultSet

internal object Database {
    private val database = TestDatabase.start(postgresImage = "postgres:18").also { it.migrer() }

    val transaksjonProvider = PostgresTransaksjonProvider(database.dataSource)
    val dataSource = database.dataSource

    fun tøm() =
        database.tøm(
            "medlemskapsvurdering_vilkarsvurdering",
            "vilkarsvurdering",
            "medlemskapsproving",
            "medlemskapsvurdering",
            "migreringslogg",
            "outbox",
        )

    fun antallRader(tabell: String) = database.antallRader(tabell)

    fun meldingstyperIOutbox(): List<String> =
        database.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT melding ->> 'type' AS type FROM outbox ORDER BY opprettet").use { rs ->
                    generateSequence { if (rs.next()) rs.getString("type") else null }.toList()
                }
            }
        }

    /**
     * Dumper innholdet i de gitte tabellene til stdout, som enkle tabeller — nyttig for å følge med på
     * hvordan datastrukturene endrer seg gjennom en test, f.eks. mellom hvert steg i en e2e-test.
     */
    fun dump(
        vararg tabeller: String,
        overskrift: String? = null,
    ) {
        database.dataSource.connection.use { connection ->
            overskrift?.let { println("\n===== $it =====") }
            for (tabell in tabeller) {
                println("--- $tabell ---")
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT * FROM $tabell").use { rs ->
                        println(rs.tilTekst())
                    }
                }
            }
        }
    }

    private fun ResultSet.tilTekst(): String {
        val kolonner = (1..metaData.columnCount).map { metaData.getColumnLabel(it) }
        val rader = mutableListOf<List<String>>()
        while (next()) {
            rader.add(kolonner.map { kolonne -> getObject(kolonne)?.toString() ?: "NULL" })
        }
        if (rader.isEmpty()) return "(ingen rader)"
        val bredder = kolonner.indices.map { i -> (listOf(kolonner[i]) + rader.map { it[i] }).maxOf { it.length } }

        fun rad(felter: List<String>) = felter.mapIndexed { i, felt -> felt.padEnd(bredder[i]) }.joinToString(" | ")
        return buildString {
            appendLine(rad(kolonner))
            appendLine(bredder.joinToString("-+-") { "-".repeat(it) })
            rader.forEach { appendLine(rad(it)) }
        }.trimEnd()
    }
}
