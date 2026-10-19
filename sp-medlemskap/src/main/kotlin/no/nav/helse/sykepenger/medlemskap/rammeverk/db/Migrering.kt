package no.nav.helse.sykepenger.medlemskap.rammeverk.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import no.nav.sykepenger.libs.logging.navngittLogger
import org.flywaydb.core.Flyway

private val logger = navngittLogger("no.nav.helse.sykepenger.medlemskap.rammeverk.db.Migrering")

fun migrerSynkront(config: DatabaseConfig) {
    logger.info("Migrerer database")
    val migreringsDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                maximumPoolSize = 2
                minimumIdle = 1
                poolName = "sp-medlemskap-migrering"
            },
        )
    migreringsDataSource.use { migreringsDataSource ->
        Flyway
            .configure()
            .dataSource(migreringsDataSource)
            .locations(*config.flywayLocations.toTypedArray())
            .cleanDisabled(true)
            .lockRetryCount(-1)
            .validateMigrationNaming(true)
            .load()
            .migrate()
    }
    logger.info("Migrering ferdig")
}
