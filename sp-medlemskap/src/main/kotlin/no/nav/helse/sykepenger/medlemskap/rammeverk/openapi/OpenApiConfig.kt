package no.nav.helse.sykepenger.medlemskap.rammeverk.openapi

data class OpenApiConfig(
    val eksponerOpenApi: Boolean = false,
    val tittel: String,
    val versjon: String = "1.0.0",
) {
    companion object {
        fun fraEnv(
            appNavn: String,
            env: Map<String, String> = System.getenv(),
        ) = OpenApiConfig(
            eksponerOpenApi = env["EKSPONER_OPENAPI"]?.toBooleanStrictOrNull() ?: false,
            tittel = appNavn,
        )
    }
}
