package no.nav.helse.sykepenger.medlemskap.bootstrap

import com.github.navikt.tbd_libs.access_token.TexasClient
import com.github.navikt.tbd_libs.personpseudoid.ValkeyPersonPseudoIdProvider
import com.github.navikt.tbd_libs.populasjonstilgang.client.PopulasjonstilgangConfig
import com.github.navikt.tbd_libs.populasjonstilgang.client.tilgangsmaskinenClient
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import no.nav.helse.rapids_rivers.RapidApplication
import no.nav.helse.sykepenger.medlemskap.application.PersonAvstemmingService
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.infra.db.PostgresTransaksjonProvider
import no.nav.helse.sykepenger.medlemskap.infra.kafka.ForhåndsvurderingRiver
import no.nav.helse.sykepenger.medlemskap.infra.kafka.GrunnlagForAutomatiskMedlemskapsvurderingRiver
import no.nav.helse.sykepenger.medlemskap.infra.kafka.GrunnlagForNyMedlemskapsvurderingRiver
import no.nav.helse.sykepenger.medlemskap.infra.kafka.MedlemskapsvurderingResultatRiver
import no.nav.helse.sykepenger.medlemskap.infra.kafka.MedlemskapsvurderingRiver
import no.nav.helse.sykepenger.medlemskap.infra.kafka.OutboxPubliseringsjobb
import no.nav.helse.sykepenger.medlemskap.infra.kafka.VentPåForhåndsvurdering
import no.nav.helse.sykepenger.medlemskap.infra.kafka.VentendeBehovjobb
import no.nav.helse.sykepenger.medlemskap.infra.rest.GetMedlemskapshistorikkBehandler
import no.nav.helse.sykepenger.medlemskap.infra.rest.GetSaksbehandlerhandlingVisningBehandler
import no.nav.helse.sykepenger.medlemskap.infra.rest.GetVilkårsvurderingerForPersonBehandler
import no.nav.helse.sykepenger.medlemskap.infra.rest.PostManuellVilkårsvurderingBehandler
import no.nav.helse.sykepenger.medlemskap.infra.rest.PostNyAutomatiskVilkårsvurderingBehandler
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisClient
import no.nav.helse.sykepenger.medlemskap.leaderelection.NaisLeaderElection
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.AzureAdConfig
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.TilgangsgrupperTilTilganger
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.configureJwtAuthentication
import no.nav.helse.sykepenger.medlemskap.rammeverk.db.DatabaseConfig
import no.nav.helse.sykepenger.medlemskap.rammeverk.db.dataSource
import no.nav.helse.sykepenger.medlemskap.rammeverk.db.migrerSynkront
import no.nav.helse.sykepenger.medlemskap.rammeverk.openapi.OpenApiConfig
import no.nav.helse.sykepenger.medlemskap.rammeverk.openapi.configureOpenApiPlugin
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureCallId
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureCallLogging
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureContentNegotiation
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureResources
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureStatusPages
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.RestAdapter
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.RestRuting
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.TransaksjonProvider
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.configureRestRuting
import no.nav.sykepenger.libs.logging.loggInfo
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val APP_NAVN = "sp-medlemskap"

data class AppKonfigurasjon(
    val appNavn: String,
    val azureAd: AzureAdConfig,
    val database: DatabaseConfig,
    val populasjonstilgang: PopulasjonstilgangConfig,
    val tilganger: TilgangsgrupperTilTilganger,
    val openApi: OpenApiConfig,
    val valkeyInstansPersonPseudoId: String = "personpseudoid",
    /**
     * Hvor lenge en prøving venter på en forhåndsvurdering fra medlemskapstjenesten før den spør
     * direkte. Er den ikke satt, spør vi alltid direkte. Settes med `FORHANDSVURDERING_VENTETID` på
     * ISO 8601-format, for eksempel `PT5M`.
     */
    val forhåndsvurderingVentetid: Duration? = null,
    /**
     * Om oppslag fra Speil lagrer personens vurderinger fra Spleis (migrering ved oppslag). Er den av,
     * leses vurderingene fra Spleis uten å lagres. Settes med `MIGRER_VED_OPPSLAG=true`, og skal ikke
     * skrus på i prod før sp-medlemskap gjør vurderingen selv.
     */
    val migrerVedOppslag: Boolean = false,
    /**
     * Om saksbehandler kan vurdere medlemskap manuelt eller be om en ny automatisk vurdering. Settes med
     * `OVERSTYRING_AV_MEDLEMSKAP=true`, og skal ikke skrus på i prod før sp-medlemskap gjør vurderingen selv.
     */
    val overstyringErSkruddPå: Boolean = false,
) {
    companion object {
        fun fraEnv(
            appNavn: String,
            env: Map<String, String> = System.getenv(),
        ) = AppKonfigurasjon(
            appNavn = appNavn,
            azureAd = AzureAdConfig.fraEnv(env),
            database = DatabaseConfig.fraEnv(env),
            populasjonstilgang = PopulasjonstilgangConfig.fraEnv(env),
            tilganger = TilgangsgrupperTilTilganger.fraEnv(env),
            openApi = OpenApiConfig.fraEnv(appNavn, env),
            forhåndsvurderingVentetid = env["FORHANDSVURDERING_VENTETID"]?.let(Duration::parse),
            migrerVedOppslag = env["MIGRER_VED_OPPSLAG"].toBoolean(),
            overstyringErSkruddPå = env["OVERSTYRING_AV_MEDLEMSKAP"].toBoolean(),
        )
    }
}

fun main() {
    val env = System.getenv()
    val versjonAvKode = env.getValue("NAIS_APP_IMAGE")
    val konfigurasjon = AppKonfigurasjon.fraEnv(APP_NAVN, env)
    val spleisClient = SpleisClient.fromEnv()

    val dataSource = konfigurasjon.database.dataSource()
    migrerSynkront(konfigurasjon.database)
    val transaksjonProvider = PostgresTransaksjonProvider(dataSource)
    lateinit var rapidsConnection: RapidsConnection
    val leaderElection =
        NaisLeaderElection(
            electorSseUrl = env.getValue("ELECTOR_SSE_URL"),
            hostname = env.getValue("HOSTNAME"),
            ventetidFørNyTilkobling = 5.seconds,
        )
    val ventPåForhåndsvurdering =
        konfigurasjon.forhåndsvurderingVentetid?.let { ventetid ->
            VentPåForhåndsvurdering(ventetid = ventetid)
        }

    RapidApplication
        .create(env, builder = {
            withKtorModule {
                ktorApp(
                    konfigurasjon = konfigurasjon,
                    transaksjonProvider = transaksjonProvider,
                    spleisClient = spleisClient,
                    env = env,
                    versjonAvKode = versjonAvKode,
                    publiser = { key, melding -> rapidsConnection.publish(key, melding) },
                )
            }
        })
        .apply {
            rapidsConnection = this
            register(leaderElection)
            GrunnlagForAutomatiskMedlemskapsvurderingRiver(
                rapidsConnection = this,
                transaksjonProvider = transaksjonProvider,
                versjonAvKode = versjonAvKode,
            )
            GrunnlagForNyMedlemskapsvurderingRiver(
                rapidsConnection = this,
                transaksjonProvider = transaksjonProvider,
                versjonAvKode = versjonAvKode,
            )
            MedlemskapsvurderingRiver(
                rapidsConnection = this,
                transaksjonProvider = transaksjonProvider,
                versjonAvKode = versjonAvKode,
                ventPåForhåndsvurdering = ventPåForhåndsvurdering,
            )
            ForhåndsvurderingRiver(
                rapidsConnection = this,
                transaksjonProvider = transaksjonProvider,
            )
            MedlemskapsvurderingResultatRiver(
                rapidsConnection = this,
                transaksjonProvider = transaksjonProvider,
                spleisClient = spleisClient,
            )
//            PersonAvstemmingRiver(
//                rapidsConnection = this,
//                personAvstemmingService =
//                    PersonAvstemmingService(
//                        transaksjonProvider = transaksjonProvider,
//                        spleisClient = spleisClient,
//                    ),
//            )
            register(
                OutboxPubliseringsjobb(
                    rapidsConnection = this,
                    transaksjonProvider = transaksjonProvider,
                    leaderElection = leaderElection,
                ),
            )
            // Registreres alltid, så prøvinger som venter blir ferdige selv om ventingen skrus av
            register(
                VentendeBehovjobb(
                    rapidsConnection = this,
                    transaksjonProvider = transaksjonProvider,
                    leaderElection = leaderElection,
                    versjonAvKode = versjonAvKode,
                ),
            )
        }.start()
}

private fun Application.ktorApp(
    konfigurasjon: AppKonfigurasjon,
    transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
    spleisClient: ISpleisClient,
    env: Map<String, String>,
    versjonAvKode: String,
    publiser: (key: String, melding: String) -> Unit,
) {
    val texasClient = TexasClient.fromEnv()
    val restAdapter =
        RestAdapter<Transaksjonskontekst>(
            personPseudoIdProvider = ValkeyPersonPseudoIdProvider.fraEnv(konfigurasjon.valkeyInstansPersonPseudoId, env),
            populasjonstilgangskontrollProvider = konfigurasjon.populasjonstilgang.tilgangsmaskinenClient(texasClient),
            transaksjonProvider = transaksjonProvider,
        )
    configureCallId()
    configureCallLogging()
    configureContentNegotiation()
    configureStatusPages()
    configureResources()
    configureJwtAuthentication(
        azureAdConfig = konfigurasjon.azureAd,
        tilgangsgrupperTilTilganger = konfigurasjon.tilganger,
    )
    configureOpenApiPlugin(konfigurasjon.openApi)
    configureRestRuting(
        restAdapter,
        endepunkter(
            spleisClient = spleisClient,
            transaksjonProvider = transaksjonProvider,
            publiser = publiser,
            migrerVedOppslag = konfigurasjon.migrerVedOppslag,
            overstyringErSkruddPå = konfigurasjon.overstyringErSkruddPå,
            versjonAvKode = versjonAvKode,
        ),
    )
    monitor.subscribe(ApplicationStarted) {
        loggInfo("Ktor-applikasjon startet", "appNavn" to konfigurasjon.appNavn)
    }
}

/**
 * Appens HTTP-endepunkter, definert ett sted. Både produksjonsappen ([main]) og LocalApp bruker
 * denne, slik at et nytt endepunkt bare trenger å registreres her for å bli med begge steder.
 */
internal fun endepunkter(
    spleisClient: ISpleisClient,
    transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
    publiser: (key: String, melding: String) -> Unit,
    migrerVedOppslag: Boolean,
    overstyringErSkruddPå: Boolean,
    versjonAvKode: String = "test",
): RestRuting<Transaksjonskontekst>.() -> Unit =
    {
        val avstemmingService = PersonAvstemmingService(transaksjonProvider, spleisClient)
        get(GetVilkårsvurderingerForPersonBehandler(avstemmingService, migrerVedOppslag))
        get(GetMedlemskapshistorikkBehandler(avstemmingService, migrerVedOppslag))
        get(GetSaksbehandlerhandlingVisningBehandler())

        post(PostManuellVilkårsvurderingBehandler(overstyringErSkruddPå))
        post(PostNyAutomatiskVilkårsvurderingBehandler(publiser, versjonAvKode, overstyringErSkruddPå))
    }
