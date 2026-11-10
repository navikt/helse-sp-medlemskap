package no.nav.helse.sykepenger.medlemskap.bootstrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppKonfigurasjonTest {
    private val fullEnv =
        mapOf(
            "AZURE_APP_CLIENT_ID" to "client-id",
            "AZURE_OPENID_CONFIG_ISSUER" to "https://issuer.example",
            "AZURE_OPENID_CONFIG_JWKS_URI" to "https://issuer.example/jwks",
            "DATABASE_JDBC_URL" to "jdbc:postgresql://localhost:5432/mydb",
            "TILGANGSMASKINEN_SCOPE" to "api://cluster.tilgangsmaskin.populasjonstilgangskontroll/.default",
            "TILGANGSMASKINEN_BASE_URL" to "http://populasjonstilgangskontroll.tilgangsmaskin",
            "TILGANG_LES" to "les-uuid",
            "TILGANG_SKRIV" to "skriv-uuid",
        )

    @Test
    fun `fraEnv bygger komplett konfigurasjon fra fullstendig miljo`() {
        val konfigurasjon = AppKonfigurasjon.fraEnv("sp-medlemskap", fullEnv)
        assertEquals("sp-medlemskap", konfigurasjon.appNavn)
        assertEquals("client-id", konfigurasjon.azureAd.clientId)
        assertEquals("jdbc:postgresql://localhost:5432/mydb", konfigurasjon.database.jdbcUrl)
        assertEquals(false, konfigurasjon.openApi.eksponerOpenApi)
    }

    @Test
    fun `migrering ved oppslag og overstyring er av med mindre de er skrudd på`() {
        val konfigurasjon = AppKonfigurasjon.fraEnv("sp-medlemskap", fullEnv)
        assertEquals(false, konfigurasjon.migrerVedOppslag)
        assertEquals(false, konfigurasjon.overstyringErSkruddPå)
    }

    @Test
    fun `migrering ved oppslag og overstyring kan skrus på`() {
        val konfigurasjon =
            AppKonfigurasjon.fraEnv(
                "sp-medlemskap",
                fullEnv + mapOf("MIGRER_VED_OPPSLAG" to "true", "OVERSTYRING_AV_MEDLEMSKAP" to "true"),
            )
        assertEquals(true, konfigurasjon.migrerVedOppslag)
        assertEquals(true, konfigurasjon.overstyringErSkruddPå)
    }
}
