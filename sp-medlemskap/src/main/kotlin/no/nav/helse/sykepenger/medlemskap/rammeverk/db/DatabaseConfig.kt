package no.nav.helse.sykepenger.medlemskap.rammeverk.db

import com.github.navikt.tbd_libs.naisful.postgres.ConnectionConfigFactory
import com.github.navikt.tbd_libs.naisful.postgres.defaultJdbcUrl
import java.time.Duration

data class HikariDefaults(
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 1,
    val idleTimeout: Duration = Duration.ofMinutes(5),
    val maxLifetime: Duration = Duration.ofMinutes(30),
    val connectionTimeout: Duration = Duration.ofSeconds(5),
    val initializationFailTimeout: Duration = Duration.ofMinutes(1),
    val leakDetectionThreshold: Duration = Duration.ofSeconds(30),
)

data class DatabaseConfig(
    val jdbcUrl: String,
    val hikariDefaults: HikariDefaults = HikariDefaults(),
    val flywayLocations: List<String> = listOf("classpath:db/migration"),
) {
    companion object {
        private const val DEFAULT_POOL_SIZE = 10

        fun fraEnv(
            env: Map<String, String> = System.getenv(),
            envVarPrefix: String? = "DATABASE",
            flywayLocations: List<String> = listOf("classpath:db/migration"),
        ): DatabaseConfig {
            val jdbcUrl =
                requireNotNull(defaultJdbcUrl(ConnectionConfigFactory.Env(env, envVarPrefix))) {
                    "Fant ikke databasekonfigurasjon i miljøvariablene (prefiks: $envVarPrefix). " +
                        "Forventet enten ${envVarPrefix}_JDBC_URL eller ${envVarPrefix}_HOST/_PORT/_DATABASE/_USERNAME/_PASSWORD."
                }
            val poolSize = env["DB_POOL_SIZE"]?.toIntOrNull() ?: DEFAULT_POOL_SIZE
            return DatabaseConfig(
                jdbcUrl = jdbcUrl,
                hikariDefaults = HikariDefaults(maximumPoolSize = poolSize),
                flywayLocations = flywayLocations,
            )
        }
    }
}
