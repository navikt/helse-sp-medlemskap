package no.nav.helse.sykepenger.medlemskap.rammeverk.rapids

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import com.github.navikt.tbd_libs.rapids_and_rivers_api.FailedMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.OutgoingMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import com.github.navikt.tbd_libs.rapids_and_rivers_api.SentMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

internal class TopicfilterTest {
    private val rapid = TestRapid()
    private val rapidTopic = rapid.rapidName()

    @Test
    fun `kunRapiden slipper bare gjennom meldinger fra rapiden`() {
        val mottatt = rapid.kunRapiden().mottatteTopics()

        rapid.send(rapidTopic, "annet.topic")

        assertEquals(listOf(rapidTopic), mottatt)
    }

    @Test
    fun `godtaEkstraTopics slipper i tillegg gjennom de valgte topicene`() {
        val mottatt = rapid.godtaEkstraTopics("ekstra.en", "ekstra.to").mottatteTopics()

        rapid.send(rapidTopic, "ekstra.en", "ekstra.to", "annet.topic")

        assertEquals(listOf(rapidTopic, "ekstra.en", "ekstra.to"), mottatt)
    }

    @Test
    fun `opt-in på en filtrert rapid slipper gjennom topicet fra den underliggende rapiden`() {
        val mottatt = rapid.kunRapiden().godtaEkstraTopics("ekstra.en").mottatteTopics()

        rapid.send(rapidTopic, "ekstra.en", "annet.topic")

        assertEquals(listOf(rapidTopic, "ekstra.en"), mottatt)
    }

    @Test
    fun `opt-in legges til topicene som allerede er godtatt`() {
        val mottatt = rapid.godtaEkstraTopics("ekstra.en").godtaEkstraTopics("ekstra.to").mottatteTopics()

        rapid.send(rapidTopic, "ekstra.en", "ekstra.to", "annet.topic")

        assertEquals(listOf(rapidTopic, "ekstra.en", "ekstra.to"), mottatt)
    }

    @Test
    fun `publiserer på den underliggende rapiden`() {
        val filtrert = rapid.kunRapiden()

        filtrert.publish("""{"a":1}""")
        filtrert.publish("nøkkel", """{"b":2}""")

        assertEquals(2, rapid.inspektør.size)
        assertEquals("nøkkel", rapid.inspektør.key(1))
        assertEquals(rapidTopic, filtrert.rapidName())
    }

    @Test
    fun `videresender statushendelser fra den underliggende rapiden`() {
        val underliggende = RapidMedStatus()
        val filtrert = underliggende.kunRapiden()
        val hendelser = mutableListOf<String>()
        filtrert.register(
            object : RapidsConnection.StatusListener {
                override fun onStartup(rapidsConnection: RapidsConnection) {
                    hendelser.add("startup")
                }

                override fun onReady(rapidsConnection: RapidsConnection) {
                    hendelser.add("ready")
                }

                override fun onShutdown(rapidsConnection: RapidsConnection) {
                    hendelser.add("shutdown")
                }
            },
        )

        underliggende.start()
        underliggende.stop()

        assertEquals(listOf("startup", "ready", "shutdown"), hendelser)
    }

    private fun RapidsConnection.mottatteTopics(): List<String> {
        val topics = mutableListOf<String>()
        register(RapidsConnection.MessageListener { _, _, metadata, _ -> topics.add(metadata.topic) })
        return topics
    }

    private fun TestRapid.send(vararg topics: String) {
        topics.forEach { sendTestMessage("{}", "nøkkel", it) }
    }

    private class RapidMedStatus : RapidsConnection() {
        override fun start() {
            notifyStartup()
            notifyReady()
        }

        override fun stop() {
            notifyShutdown()
        }

        override fun publish(message: String) = Unit

        override fun publish(
            key: String,
            message: String,
        ) = Unit

        override fun publish(messages: List<OutgoingMessage>) = emptyList<SentMessage>() to emptyList<FailedMessage>()

        override fun rapidName() = "rapid"
    }
}
