package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.nav.helse.sykepenger.medlemskap.application.MedlemskapService
import no.nav.helse.sykepenger.medlemskap.application.MedlemskapService.BehandleVentendeBehovResultat
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.leaderelection.LeaderElection
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.TransaksjonProvider
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggError
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.medMdc
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Går jevnlig gjennom behovene som venter på en forhåndsvurdering, se [MedlemskapService.behandleVentendeBehov].
 * Har prøvingen fått forhåndsvurderingen, svarer jobben på det opprinnelige behovet fra Spleis. Har
 * fristen gått ut, sender den behovet `Medlemskap`, og resten går som før via
 * [GrunnlagForAutomatiskMedlemskapsvurderingRiver].
 *
 * Svaret publiseres etter at transaksjonen er commitet. Går publiseringen galt, får Spleis svar neste
 * gang den sender behovet: da finnes vurderingen, eller prøvingen venter på `Medlemskap` og behovet
 * sendes på nytt.
 */
internal class VentendeBehovjobb(
    private val rapidsConnection: RapidsConnection,
    private val transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
    private val leaderElection: LeaderElection,
    private val versjonAvKode: String = "test",
    private val klokke: Clock = Clock.systemUTC(),
    private val pollIntervall: Duration = 5.seconds,
    private val maksAntallPerRunde: Int = 100,
) : RapidsConnection.StatusListener {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    override fun onStartup(rapidsConnection: RapidsConnection) {
        loggInfo("Starter jobb for ventende behov")
        job =
            scope.launch {
                while (isActive) {
                    kjørEnRundeHvisLeader()
                    delay(pollIntervall)
                }
            }
    }

    override fun onShutdownSignal(rapidsConnection: RapidsConnection) {
        loggInfo("Stopper jobb for ventende behov")
        runBlocking {
            job?.cancelAndJoin()
        }
    }

    internal fun kjørEnRundeHvisLeader() {
        if (leaderElection.isLeader()) kjørEnRunde()
    }

    internal fun kjørEnRunde() {
        val ventende =
            try {
                transaksjonProvider.transaksjon { it.ventendeBehov.alle(maksAntallPerRunde) }
            } catch (e: Exception) {
                loggError("Klarte ikke hente ventende behov. Prøver igjen ved neste runde", e)
                return
            }
        ventende.forEach { medlemskapsprøvingId ->
            try {
                val resultat =
                    transaksjonProvider.transaksjon { kontekst ->
                        MedlemskapService(kontekst, versjonAvKode).behandleVentendeBehov(medlemskapsprøvingId, klokke.instant())
                    }
                publiser(resultat)
            } catch (e: Exception) {
                loggError(
                    "Feil under behandling av ventende behov. Prøver igjen ved neste runde",
                    e,
                    "medlemskapsprøvingId" to medlemskapsprøvingId.toString(),
                )
            }
        }
    }

    private fun publiser(resultat: BehandleVentendeBehovResultat) {
        when (resultat) {
            BehandleVentendeBehovResultat.VenterFortsatt,
            BehandleVentendeBehovResultat.IkkeLengerAktuelt,
            -> Unit

            is BehandleVentendeBehovResultat.NyVurderingForetatt ->
                medMdc(MdcKey.IDENTITETSNUMMER to resultat.behov.fødselsnummer) {
                    val opprinneligBehov = objectMapper.readTree(resultat.behov.opprinneligBehov) as ObjectNode
                    opprinneligBehov
                        .putObject("@løsning")
                        .putObject("Medlemskapsvurdering")
                        .put("id", resultat.medlemskapsvurderingId.toString())
                    val løsning = opprinneligBehov.toString()
                    loggInfo(
                        "Publiserer løsning på behov for medlemskapsvurdering",
                        "medlemskapsvurderingId" to resultat.medlemskapsvurderingId.toString(),
                        "løsning" to løsning,
                    )
                    rapidsConnection.publish(resultat.behov.fødselsnummer, løsning)
                }

            is BehandleVentendeBehovResultat.TrengerMedlemskapsopplysninger ->
                medMdc(MdcKey.IDENTITETSNUMMER to resultat.behov.fødselsnummer) {
                    val opplysninger = resultat.opplysninger
                    val utgåendeBehov =
                        Medlemskapsbehov.nytt(
                            fødselsnummer = opplysninger.fødselsnummer,
                            vedtaksperiodeId = resultat.behov.vedtaksperiodeId,
                            skjæringstidspunkt = opplysninger.skjæringstidspunkt,
                            medlemskapsprøvingId = opplysninger.medlemskapsprøvingId,
                            oppslagsperiode = opplysninger.oppslagsperiode,
                            ekstra = mapOf("opprinneligBehov" to objectMapper.readTree(resultat.behov.opprinneligBehov)),
                        )
                    loggInfo(
                        "Trenger medlemskapsopplysninger. Publiserer nytt behov",
                        "behov" to utgåendeBehov.toJson(),
                        "medlemskapsprøvingId" to opplysninger.medlemskapsprøvingId.toString(),
                        "skjæringstidspunkt" to opplysninger.skjæringstidspunkt.toString(),
                    )
                    rapidsConnection.publish(opplysninger.fødselsnummer, utgåendeBehov.toJson())
                }
        }
    }

    private companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}
