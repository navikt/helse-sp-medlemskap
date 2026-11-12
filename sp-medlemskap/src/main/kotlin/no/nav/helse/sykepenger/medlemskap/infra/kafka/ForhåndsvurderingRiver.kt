package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers.asLocalDate
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageProblems
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import no.nav.helse.Periode
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.domain.Forhåndsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.ForhåndsvurderingId
import no.nav.helse.sykepenger.medlemskap.rammeverk.rapids.godtaEkstraTopics
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.TransaksjonProvider
import no.nav.helse.til
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggError
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.medMdc
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * Lagrer forhåndsvurderingene medlemskapstjenesten (LovMe) sender på topicet
 * `medlemskap.medlemskapsvurderinger`. Topicet leses i tillegg til rapiden, via `KAFKA_EXTRA_TOPIC`,
 * og riveren velger det med [godtaEkstraTopics].
 *
 * ```json
 * { "soknadId": "<søknadens eksterne id>", "fnr": "...", "speilSvar": "UAVKLART_MED_BRUKERSPORSMAAL",
 *   "gyldigForSkjaeringstidspunktFom": "2018-01-01", "gyldigForSkjaeringstidspunktTom": "2018-01-31",
 *   "aarsaker": [{ "regelId": "REGEL_3", "forklaringPaaUavklart": "..." }] }
 * ```
 *
 * Vurderingen gjelder skjæringstidspunkter fra og med `gyldigForSkjaeringstidspunktFom` til og med
 * `gyldigForSkjaeringstidspunktTom`. Meldinger uten datoene lagres, men blir aldri brukt. Meldinger
 * med bare én av datoene, eller med fom etter tom, avvises.
 *
 * Prøvinger som venter på forhåndsvurderingen, plukker den opp i [VentendeBehovjobb].
 */
internal class ForhåndsvurderingRiver(
    rapidsConnection: RapidsConnection,
    private val transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
) : River.PacketListener {
    init {
        River(rapidsConnection.godtaEkstraTopics(TOPIC))
            .apply {
                precondition {
                    it.forbid("@event_name")
                    it.requireKey("soknadId", "fnr", "speilSvar")
                }
                validate {
                    it.require("soknadId", JsonNode::asUUID)
                    it.requireAny("speilSvar", Medlemskapsbehov.svar.keys.toList())
                    it.interestedIn("aarsaker") { node -> Medlemskapsbehov.lesAarsaker(node, "aarsaker") }
                    it.interestedIn(GYLDIG_FOM, GYLDIG_TOM)
                    // Kjøres bare når feltet finnes, så sjekken legges på begge for å fange at bare ett av dem finnes
                    listOf(GYLDIG_FOM, GYLDIG_TOM).forEach { felt -> it.interestedIn(felt) { _ -> gyldigForSkjæringstidspunkt(it) != null } }
                }
            }.register(this)
    }

    override fun onPacket(
        packet: JsonMessage,
        context: MessageContext,
        metadata: MessageMetadata,
        meterRegistry: MeterRegistry,
    ) {
        val fødselsnummer = packet["fnr"].asString()
        val forhåndsvurdering =
            Forhåndsvurdering(
                id = ForhåndsvurderingId.ny(),
                søknadId = packet["soknadId"].asUUID(),
                fødselsnummer = fødselsnummer,
                gyldigForSkjæringstidspunkt = gyldigForSkjæringstidspunkt(packet),
                status = Medlemskapsbehov.svar.getValue(packet["speilSvar"].asString()),
                aarsaker = packet["aarsaker"].takeUnless { it.isMissingNode || it.isNull }?.let { Medlemskapsbehov.lesAarsaker(it, "aarsaker") },
                mottatt = Instant.now(),
            )
        medMdc(
            MdcKey.IDENTITETSNUMMER to fødselsnummer,
        ) {
            transaksjonProvider.transaksjon { it.forhåndsvurderinger.lagre(forhåndsvurdering, packet.toJson()) }
            loggInfo(
                "Lagret forhåndsvurdering fra medlemskapstjenesten",
                "forhåndsvurderingId" to forhåndsvurdering.id.toString(),
                "søknadId" to forhåndsvurdering.søknadId.toString(),
                "gyldigForSkjæringstidspunkt" to forhåndsvurdering.gyldigForSkjæringstidspunkt.toString(),
                "status" to forhåndsvurdering.status.name,
            )
        }
    }

    override fun onError(
        problems: MessageProblems,
        context: MessageContext,
        metadata: MessageMetadata,
    ) {
        loggError("Ugyldig forhåndsvurdering fra medlemskapstjenesten", "feil" to problems.toString())
    }

    companion object {
        /** Topicet medlemskapstjenesten sender forhåndsvurderingene på. */
        const val TOPIC = "medlemskap.medlemskapsvurderinger"

        private const val GYLDIG_FOM = "gyldigForSkjaeringstidspunktFom"
        private const val GYLDIG_TOM = "gyldigForSkjaeringstidspunktTom"

        /** Begge datoene eller ingen av dem. Kaster ved ugyldige datoer, så meldingen avvises. */
        private fun gyldigForSkjæringstidspunkt(packet: JsonMessage): Periode? {
            val fom = packet[GYLDIG_FOM].datoOrNull()
            val tom = packet[GYLDIG_TOM].datoOrNull()
            if (fom == null && tom == null) return null
            requireNotNull(fom) { "$GYLDIG_FOM mangler" }
            requireNotNull(tom) { "$GYLDIG_TOM mangler" }
            require(fom <= tom) { "$GYLDIG_FOM er etter $GYLDIG_TOM" }
            return fom til tom
        }

        private fun JsonNode.datoOrNull() = takeUnless { it.isMissingNode || it.isNull }?.asLocalDate()
    }
}
