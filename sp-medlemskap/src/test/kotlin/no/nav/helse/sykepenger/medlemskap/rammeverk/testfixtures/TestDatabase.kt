package no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource

/**
 * Postgres-testcontainer med Flyway-migrering og generelle databasehjelpere for integrasjonstester.
 *
 * Opprett én instans per testkjøring når testene skal dele en langlevende database. Kall [tøm]
 * mellom testene for å isolere testdataene.
 */
class TestDatabase private constructor(
    private val container: PostgreSQLContainer,
    val dataSource: DataSource,
) : AutoCloseable {
    fun migrer(locations: List<String> = listOf("classpath:db/migration")) {
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations(*locations.toTypedArray())
            .cleanDisabled(true)
            .load()
            .migrate()
    }

    fun tøm(vararg tabeller: String) {
        require(tabeller.isNotEmpty()) { "Minst én tabell må oppgis" }
        tabeller.forEach(::validerTabellnavn)

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("truncate table ${tabeller.joinToString()} restart identity")
            }
        }
    }

    fun antallRader(tabell: String): Int {
        validerTabellnavn(tabell)
        return dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(1) from $tabell").use { resultSet ->
                    check(resultSet.next()) { "Spørringen returnerte ingen rad for tabellen $tabell" }
                    resultSet.getInt(1)
                }
            }
        }
    }

    fun stop() {
        (dataSource as? HikariDataSource)?.close()
        container.stop()
    }

    override fun close() = stop()

    companion object {
        private val gyldigTabellnavn = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?")

        fun start(
            postgresImage: String = "postgres:18-alpine",
            maximumPoolSize: Int = 3,
        ): TestDatabase {
            require(maximumPoolSize > 0) { "maximumPoolSize må være større enn 0" }

            val container = PostgreSQLContainer(postgresImage).apply { start() }
            val dataSource =
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = container.jdbcUrl
                        username = container.username
                        password = container.password
                        this.maximumPoolSize = maximumPoolSize
                    },
                )
            return TestDatabase(container, dataSource)
        }

        private fun validerTabellnavn(tabell: String) {
            require(gyldigTabellnavn.matches(tabell)) { "Ugyldig tabellnavn: $tabell" }
        }
    }
}
