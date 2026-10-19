package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.OutgoingMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.nav.helse.sykepenger.medlemskap.application.OutboxMelding
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.leaderelection.LeaderElection
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.TransaksjonProvider
import no.nav.sykepenger.libs.logging.loggError
import no.nav.sykepenger.libs.logging.loggInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class OutboxPubliseringsjobb(
    private val rapidsConnection: RapidsConnection,
    private val transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
    private val leaderElection: LeaderElection,
    private val pollIntervall: Duration = 0.5.seconds,
) : RapidsConnection.StatusListener {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    override fun onStartup(rapidsConnection: RapidsConnection) {
        loggInfo("Starter outbox-publiseringsjobb")
        job =
            scope.launch {
                while (isActive) {
                    kjørEnRundeHvisLeader()
                    delay(pollIntervall)
                }
            }
    }

    override fun onShutdownSignal(rapidsConnection: RapidsConnection) {
        loggInfo("Stopper outbox-publiseringsjobb")
        runBlocking {
            job?.cancelAndJoin()
        }
    }

    internal fun kjørEnRunde() {
        try {
            var harMelding: Boolean
            do {
                harMelding =
                    transaksjonProvider.transaksjon { kontekst ->
                        val konvolutt = kontekst.outbox.hentUpubliserte(maksAntall = 1).firstOrNull() ?: return@transaksjon false
                        val json =
                            when (val melding = konvolutt.melding) {
                                is OutboxMelding.MedlemskapsvurderingEndret -> melding.tilJsonMessage(konvolutt.identitetsnummer)
                                is OutboxMelding.Saksbehandlerhandling -> melding.tilJsonMessage(konvolutt.identitetsnummer)
                            }
                        val (_, feiledeMeldinger) =
                            rapidsConnection.publish(
                                listOf(OutgoingMessage(body = json.toJson(), key = konvolutt.identitetsnummer.value)),
                            )
                        feiledeMeldinger.firstOrNull()?.apply { throw error }
                        kontekst.outbox.markerSomSendt(konvolutt.id)
                        true
                    }
            } while (harMelding)
        } catch (e: Exception) {
            loggError("Feil under publisering av outbox-meldinger. Prøver igjen ved neste poll", e)
        }
    }

    internal fun kjørEnRundeHvisLeader() {
        if (leaderElection.isLeader()) kjørEnRunde()
    }

    private fun OutboxMelding.MedlemskapsvurderingEndret.tilJsonMessage(identitetsnummer: Identitetsnummer) =
        JsonMessage.newMessage(
            eventName = "endret_medlemskapsvurdering",
            map =
                mapOf(
                    "fødselsnummer" to identitetsnummer,
                    "skjæringstidspunkt" to skjæringstidspunkt,
                    "medlemskapsvurderingId" to medlemskapsvurderingId,
                    "manuellVurdering" to manuellVurdering,
                ),
        )

    private fun OutboxMelding.Saksbehandlerhandling.tilJsonMessage(identitetsnummer: Identitetsnummer) =
        JsonMessage.newMessage(
            eventName = "saksbehandlerhandling",
            map =
                mapOf(
                    "fødselsnummer" to identitetsnummer,
                    "skjæringstidspunkt" to skjæringstidspunkt,
                    "saksbehandlerIdent" to saksbehandlerIdent,
                    "kreverTotrinnsvurdering" to kreverTotrinnsvurdering,
                    "handlingId" to handlingId,
                    "handlingType" to handlingType,
                    "tidspunkt" to tidspunkt,
                ),
        )
}
