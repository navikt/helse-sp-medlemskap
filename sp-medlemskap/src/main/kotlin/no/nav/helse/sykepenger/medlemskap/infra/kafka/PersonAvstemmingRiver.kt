package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import no.nav.helse.sykepenger.medlemskap.application.PersonAvstemmingService
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.medMdc

internal class PersonAvstemmingRiver(
    rapidsConnection: RapidsConnection,
    private val personAvstemmingService: PersonAvstemmingService,
) : River.PacketListener {
    private val eventName = "person_avstemming"

    init {
        River(rapidsConnection)
            .apply {
                precondition { it.requireValue("@event_name", eventName) }
                validate {
                    it.requireKey("@id", "fødselsnummer")
                }
            }.register(this)
    }

    override fun onPacket(
        packet: JsonMessage,
        context: MessageContext,
        metadata: MessageMetadata,
        meterRegistry: MeterRegistry,
    ) {
        val fødselsnummer = packet["fødselsnummer"].asString()

        medMdc(
            MdcKey.IDENTITETSNUMMER to fødselsnummer,
            MdcKey.MELDING_ID to packet["@id"].asString(),
        ) {
            loggInfo("Mottatt $eventName")
            personAvstemmingService.lagreMedlemskapsvurderinger(fødselsnummer)
        }
    }
}
