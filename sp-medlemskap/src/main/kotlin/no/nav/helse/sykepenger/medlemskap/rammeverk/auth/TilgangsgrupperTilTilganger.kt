package no.nav.helse.sykepenger.medlemskap.rammeverk.auth

class TilgangsgrupperTilTilganger(
    private val tilgangLesGruppeIder: Set<String>,
    private val tilgangSkrivGruppeIder: Set<String>,
) {
    /**
     * Skrivetilgang impliserer lesetilgang: en saksbehandler i skrivegruppa er ikke nødvendigvis
     * medlem av lesegruppa i Entra, og ville ellers fått 403 på alle `Tilgang.Les`-endepunkter.
     * Samme semantikk som spesialist (`TilgangsgrupperTilTilganger.finnTilgangerFraTilgangsgrupper`).
     */
    fun tilganger(entraGrupper: Set<String>): Set<Tilgang> =
        buildSet {
            if (tilgangSkrivGruppeIder.any { it in entraGrupper }) {
                add(Tilgang.Skriv)
                add(Tilgang.Les)
            }
            if (tilgangLesGruppeIder.any { it in entraGrupper }) add(Tilgang.Les)
        }

    companion object {
        // TILGANG_LES/TILGANG_SKRIV kan inneholde én eller flere Entra-gruppe-UUID-er,
        // kommaseparert (f.eks. når flere grupper skal gi samme tilgangsnivå).
        fun fraEnv(env: Map<String, String> = System.getenv()) =
            TilgangsgrupperTilTilganger(
                tilgangLesGruppeIder = grupperFra(env.getValue("TILGANG_LES")),
                tilgangSkrivGruppeIder = grupperFra(env.getValue("TILGANG_SKRIV")),
            )

        private fun grupperFra(verdi: String): Set<String> =
            verdi
                .split(",")
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
    }
}
