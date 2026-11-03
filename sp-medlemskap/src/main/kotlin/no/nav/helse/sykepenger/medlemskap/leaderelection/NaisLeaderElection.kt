package no.nav.helse.sykepenger.medlemskap.leaderelection

import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import no.nav.sykepenger.libs.logging.loggError
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.loggWarn
import org.apache.hc.client5.http.fluent.Request
import org.apache.hc.core5.http.HttpStatus
import org.apache.hc.core5.util.Timeout
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.concurrent.thread
import kotlin.time.Duration

/**
 * Abonnerer på SSE-strømmen til elector-sidecaren som Nais injiserer ved `leaderElection: true`, og cacher
 * hvilken pod som er leader. Elector sender gjeldende leader straks vi kobler til, og deretter en ny hendelse
 * hver gang leader endres. Se https://doc.nais.io/services/leader-election/
 *
 * Vi beholder siste kjente leader også når strømmen er nede. Det er trygt fordi en pod er leader til den
 * slettes: Er vi leader, forblir vi det. Er vi ikke leader, kan vi i verste fall gå glipp av å bli det til vi
 * har koblet til igjen.
 */
internal class NaisLeaderElection(
    private val electorSseUrl: String,
    private val hostname: String,
    private val ventetidFørNyTilkobling: Duration,
) : LeaderElection,
    RapidsConnection.StatusListener {
    private val objectMapper = jacksonObjectMapper()

    @Volatile
    private var leader: String? = null

    @Volatile
    private var stoppet = false

    override fun isLeader(): Boolean = leader == hostname

    override fun onStartup(rapidsConnection: RapidsConnection) {
        thread(name = "leader-election", isDaemon = true) { lyttPåElector() }
    }

    override fun onShutdownSignal(rapidsConnection: RapidsConnection) {
        stoppet = true
    }

    private fun lyttPåElector() {
        var feiledeTilkoblingerPåRad = 0
        while (!stoppet) {
            try {
                lesStrømmenTilDenLukkes()
                feiledeTilkoblingerPåRad = 0
                loggInfo("Elector lukket SSE-strømmen. Kobler til på nytt")
            } catch (e: Exception) {
                feiledeTilkoblingerPåRad++
                if (feiledeTilkoblingerPåRad >= TILKOBLINGSFEIL_FØR_ERROR) {
                    loggError("Har ikke klart å lytte på leader-endringer fra elector på en stund", e)
                } else {
                    loggWarn("Mistet eller fikk ikke kontakt med elector. Prøver igjen", e)
                }
            }
            Thread.sleep(ventetidFørNyTilkobling.inWholeMilliseconds)
        }
    }

    internal fun lesStrømmenTilDenLukkes() {
        Request
            .get(electorSseUrl)
            .connectTimeout(Timeout.ofSeconds(1))
            // Strømmen kan være stille i dagevis når leader ikke endres
            .responseTimeout(Timeout.DISABLED)
            .setHeader("Accept", "text/event-stream")
            .execute()
            .handleResponse { response ->
                check(response.code == HttpStatus.SC_OK) { "Fikk HTTP ${response.code} fra elector" }
                checkNotNull(response.entity) { "Fikk tom respons fra elector" }
                    .content
                    .bufferedReader()
                    .useLines { linjer ->
                        linjer
                            .filter { it.startsWith("data:") }
                            .forEach { oppdaterLeader(it.removePrefix("data:").trim()) }
                    }
            }
    }

    private fun oppdaterLeader(data: String) {
        val nyLeader =
            objectMapper
                .readTree(data)
                .path("name")
                .asString()
                .ifBlank { null }
        if (nyLeader != leader) {
            loggInfo(
                "Leader er endret",
                "leader" to nyLeader,
                "erLeader" to (nyLeader == hostname).toString(),
            )
        }
        leader = nyLeader
    }

    private companion object {
        const val TILKOBLINGSFEIL_FØR_ERROR = 12
    }
}
