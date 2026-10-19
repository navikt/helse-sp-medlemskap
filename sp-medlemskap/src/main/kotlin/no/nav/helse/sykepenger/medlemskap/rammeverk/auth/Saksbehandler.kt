package no.nav.helse.sykepenger.medlemskap.rammeverk.auth

/** Innloggede saksbehandlers identitet, hentet fra Azure AD-token-claims. */
@JvmInline
value class NavIdent(
    val value: String,
)

@JvmInline
value class SaksbehandlerOid(
    val value: String,
)

data class Saksbehandler(
    val navIdent: NavIdent,
    val oid: SaksbehandlerOid,
    val navn: String,
)

/** Rå JWT-bearer-tokenet til innlogget saksbehandler, brukes videre til OBO-token-veksling (Texas). */
@JvmInline
value class AccessToken(
    val value: String,
)
