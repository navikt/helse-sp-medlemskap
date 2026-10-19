package no.nav.helse.sykepenger.medlemskap.rammeverk.auth

data class SaksbehandlerPrincipal(
    val saksbehandler: Saksbehandler,
    val tilganger: Set<Tilgang>,
    val accessToken: AccessToken,
)
