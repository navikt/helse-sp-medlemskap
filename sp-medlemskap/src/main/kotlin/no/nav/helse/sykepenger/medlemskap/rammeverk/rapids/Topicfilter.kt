package no.nav.helse.sykepenger.medlemskap.rammeverk.rapids

import com.github.navikt.tbd_libs.rapids_and_rivers_api.FailedMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.OutgoingMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import com.github.navikt.tbd_libs.rapids_and_rivers_api.SentMessage
import io.micrometer.core.instrument.MeterRegistry

/**
 * Gir bare meldinger fra rapiden videre til rivers som registreres på den returnerte forbindelsen.
 * Meldinger fra topicene i `KAFKA_EXTRA_TOPIC` stoppes.
 *
 * Rapids-and-rivers leser rapiden og alle topicene i `KAFKA_EXTRA_TOPIC` med samme consumer, og gir
 * alle meldingene til alle rivers. En river som bare er ment for rapiden, får dermed også meldinger
 * fra andre teams topics. Gi derfor alle rivers denne forbindelsen, og la rivers som skal lese et
 * ekstra topic, velge det med [godtaEkstraTopics].
 */
internal fun RapidsConnection.kunRapiden(): RapidsConnection = Topicfilter(underliggende(), emptySet())

/**
 * Gir meldinger fra rapiden og fra [topics] videre til rivers som registreres på den returnerte
 * forbindelsen. Topicene må også stå i `KAFKA_EXTRA_TOPIC`, ellers leses de ikke.
 *
 * Kalles den på en forbindelse fra [kunRapiden], legges [topics] til de som allerede er godtatt.
 */
internal fun RapidsConnection.godtaEkstraTopics(vararg topics: String): RapidsConnection = Topicfilter(underliggende(), godtatteEkstraTopics() + topics)

private fun RapidsConnection.underliggende() = if (this is Topicfilter) underliggende else this

private fun RapidsConnection.godtatteEkstraTopics() = if (this is Topicfilter) ekstraTopics else emptySet()

/**
 * Lar bare meldinger fra rapiden og fra [ekstraTopics] nå rivers som er registrert på filteret.
 * Rapid-topicet er det [RapidsConnection.rapidName] returnerer. Publisering og statushendelser går
 * uendret til og fra [underliggende].
 */
private class Topicfilter(
    val underliggende: RapidsConnection,
    val ekstraTopics: Set<String>,
) : RapidsConnection(),
    RapidsConnection.MessageListener,
    RapidsConnection.StatusListener {
    init {
        underliggende.register(this as MessageListener)
        underliggende.register(this as StatusListener)
    }

    override fun onMessage(
        message: String,
        context: MessageContext,
        metadata: MessageMetadata,
        metrics: MeterRegistry,
    ) {
        if (metadata.topic != underliggende.rapidName() && metadata.topic !in ekstraTopics) return
        notifyMessage(message, context, metadata, metrics)
    }

    override fun onStartup(rapidsConnection: RapidsConnection) = notifyStartup()

    override fun onReady(rapidsConnection: RapidsConnection) = notifyReady()

    override fun onNotReady(rapidsConnection: RapidsConnection) = notifyNotReady()

    override fun onShutdownSignal(rapidsConnection: RapidsConnection) = notifyShutdownSignal()

    override fun onShutdown(rapidsConnection: RapidsConnection) = notifyShutdown()

    override fun onShutdownComplete(rapidsConnection: RapidsConnection) = notifyShutdownComplete()

    override fun publish(message: String) = underliggende.publish(message)

    override fun publish(
        key: String,
        message: String,
    ) = underliggende.publish(key, message)

    override fun publish(messages: List<OutgoingMessage>): Pair<List<SentMessage>, List<FailedMessage>> = underliggende.publish(messages)

    override fun rapidName() = underliggende.rapidName()

    override fun start() = underliggende.start()

    override fun stop() = underliggende.stop()
}
