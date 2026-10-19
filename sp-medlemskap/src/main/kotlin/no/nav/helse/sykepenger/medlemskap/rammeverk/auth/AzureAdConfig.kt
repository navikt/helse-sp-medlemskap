package no.nav.helse.sykepenger.medlemskap.rammeverk.auth

data class AzureAdConfig(
    val clientId: String,
    val issuerUrl: String,
    val jwkProviderUri: String,
) {
    companion object {
        fun fraEnv(env: Map<String, String> = System.getenv()) =
            AzureAdConfig(
                clientId = env.getValue("AZURE_APP_CLIENT_ID"),
                issuerUrl = env.getValue("AZURE_OPENID_CONFIG_ISSUER"),
                jwkProviderUri = env.getValue("AZURE_OPENID_CONFIG_JWKS_URI"),
            )
    }
}
