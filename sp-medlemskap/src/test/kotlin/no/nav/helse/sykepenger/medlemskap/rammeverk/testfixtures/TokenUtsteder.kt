package no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures

import no.nav.security.mock.oauth2.MockOAuth2Server
import java.util.UUID

/**
 * Utsteder JWT-tokens fra en [MockOAuth2Server] med claims tilsvarende ekte Azure AD-tokens
 * (`NAVident`, `oid`, `name`, `preferred_username`, `groups`), slik at appens auth-oppsett kan testes
 * uten en ekte Entra-tenant.
 */
class TokenUtsteder(
    private val mockOAuth2Server: MockOAuth2Server,
    private val issuerId: String = "azuread",
    private val audience: String = "test-client-id",
) {
    fun utstedSaksbehandlerToken(
        navIdent: String = "Z999999",
        oid: String = UUID.randomUUID().toString(),
        navn: String = "Test Testesen",
        entraGrupper: Set<String> = emptySet(),
    ): String =
        mockOAuth2Server
            .issueToken(
                issuerId = issuerId,
                subject = oid,
                audience = audience,
                claims =
                    mapOf(
                        "NAVident" to navIdent,
                        "oid" to oid,
                        "name" to navn,
                        "preferred_username" to navn,
                        "groups" to entraGrupper.toList(),
                    ),
            ).serialize()
}
