package no.nav.helse.sykepenger.medlemskap.rammeverk.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DatabaseConfigTest {
    @Test
    fun `bygger jdbc-url fra DATABASE_JDBC_URL`() {
        val env = mapOf("DATABASE_JDBC_URL" to "jdbc:postgresql://localhost:5432/mydb?user=u&password=p")
        val config = DatabaseConfig.fraEnv(env)
        assertEquals("jdbc:postgresql://localhost:5432/mydb?user=u&password=p", config.jdbcUrl)
    }

    @Test
    fun `bygger jdbc-url fra enkeltvariabler naar _JDBC_URL mangler`() {
        val env =
            mapOf(
                "DATABASE_HOST" to "localhost",
                "DATABASE_PORT" to "5432",
                "DATABASE_DATABASE" to "mydb",
                "DATABASE_USERNAME" to "u",
                "DATABASE_PASSWORD" to "p",
            )
        val config = DatabaseConfig.fraEnv(env)
        assertEquals(true, config.jdbcUrl.startsWith("jdbc:postgresql://localhost:5432/mydb?"))
    }

    @Test
    fun `default poolstoerrelse er 10 uten DB_POOL_SIZE`() {
        val env = mapOf("DATABASE_JDBC_URL" to "jdbc:postgresql://localhost:5432/mydb")
        val config = DatabaseConfig.fraEnv(env)
        assertEquals(10, config.hikariDefaults.maximumPoolSize)
    }

    @Test
    fun `DB_POOL_SIZE overstyrer default poolstoerrelse, f eks 5 i dev`() {
        val env =
            mapOf(
                "DATABASE_JDBC_URL" to "jdbc:postgresql://localhost:5432/mydb",
                "DB_POOL_SIZE" to "5",
            )
        val config = DatabaseConfig.fraEnv(env)
        assertEquals(5, config.hikariDefaults.maximumPoolSize)
    }

    @Test
    fun `kaster tydelig feil naar databasekonfigurasjon mangler helt`() {
        assertThrows(IllegalArgumentException::class.java) {
            DatabaseConfig.fraEnv(emptyMap())
        }
    }
}
