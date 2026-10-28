package no.nav.helse.sykepenger.medlemskap.bootstrap

import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import no.nav.helse.sykepenger.medlemskap.application.InMemoryTransaksjonProvider
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.AZURE_AD_AUTHENTICATION_NAME
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.AccessToken
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.NavIdent
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Saksbehandler
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.SaksbehandlerOid
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.SaksbehandlerPrincipal
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Tilgang
import no.nav.helse.sykepenger.medlemskap.rammeverk.openapi.OpenApiConfig
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.RestAdapter
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.configureRestRuting
import no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures.InMemoryPersonPseudoIdProvider
import no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures.installTestPlugins
import java.util.UUID

// Porten Speil forventer for sp-medlemskap (lokalPort i speil/src/app/api/backends.ts)
private const val PORT = 8383

fun main() {
    val transaksjonProvider = InMemoryTransaksjonProvider()
    val server =
        embeddedServer(CIO, port = PORT) {
            installTestPlugins(
                openApiConfig = OpenApiConfig(eksponerOpenApi = true, tittel = "sp-medlemskap"),
            )
            installerLokalAutentisering()
            installerLokaltTokenEndepunkt()
            configureRestRuting(
                restAdapter =
                    RestAdapter<Transaksjonskontekst>(
                        personPseudoIdProvider = InMemoryPersonPseudoIdProvider(),
                        populasjonstilgangskontrollProvider = TillatAltPopulasjonstilgangskontrollProvider(),
                        transaksjonProvider = transaksjonProvider,
                    ),
                // Rutene defineres i App.kt, slik at LocalApp automatisk får nye endepunkter.
                endepunkter =
                    endepunkter(
                        spleisClient = TomSpleisClient(),
                        transaksjonProvider = transaksjonProvider,
                        publiser = { _, melding -> println("Publiserer (ikke koblet til Kafka lokalt): $melding") },
                    ),
            )
        }

    server.start(wait = false)
    println("LocalApp startet.")
    println("OpenAPI: http://localhost:$PORT/api/openapi.json")
    println("Swagger UI: http://localhost:$PORT/api/swagger")
    Thread.currentThread().join()
}

/**
 * `configureRestRuting` legger alle rutene bak `authenticate(AZURE_AD_AUTHENTICATION_NAME)`. Lokalt
 * finnes det ingen Azure AD å snakke med, så vi registrerer en provider med samme navn som slipper
 * alle inn som en fast, oppdiktet saksbehandler.
 */
private fun Application.installerLokalAutentisering() {
    val principal =
        SaksbehandlerPrincipal(
            saksbehandler =
                Saksbehandler(
                    navIdent = NavIdent("Z999999"),
                    oid = SaksbehandlerOid(UUID.nameUUIDFromBytes("lokal-saksbehandler".toByteArray()).toString()),
                    navn = "Lokal Saksbehandler",
                ),
            tilganger = Tilgang.entries.toSet(),
            accessToken = AccessToken(LOKALT_TOKEN),
        )

    install(Authentication) {
        provider(AZURE_AD_AUTHENTICATION_NAME) {
            authenticate { context -> context.principal(principal) }
        }
    }
}

/**
 * Speil henter et token fra `/local-token` når backenden kjører lokalt. Den lokale autentiseringen
 * slipper alle inn, så tokenet trenger ikke å være gyldig.
 */
private fun Application.installerLokaltTokenEndepunkt() {
    routing {
        get("/local-token") { call.respondText(LOKALT_TOKEN) }
    }
}

private const val LOKALT_TOKEN = "lokalt-token"

private class TomSpleisClient : ISpleisClient {
    override fun hentMedlemskapsvurderinger(fødselsnummer: String): List<SpleisMedlemskapsvurdering> = emptyList()
}

private class TillatAltPopulasjonstilgangskontrollProvider : PopulasjonstilgangskontrollProvider {
    override fun kontrollerKomplettTilgang(
        accessToken: String,
        fødselsnummer: String,
    ) = TilgangskontrollResultat.Ok

    override fun kontrollerKjerneTilgang(
        accessToken: String,
        fødselsnummer: String,
    ) = TilgangskontrollResultat.Ok

    override fun kontrollerKjerneTilgangForAnsatt(
        ansattId: String,
        fødselsnummer: String,
    ) = TilgangskontrollResultat.Ok
}
