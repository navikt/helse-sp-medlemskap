package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers.asLocalDate
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import no.nav.helse.sykepenger.medlemskap.application.MedlemskapService
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.application.VurderMedlemskapResultat
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidssituasjon
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.TransaksjonProvider
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.medMdc
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

internal class MedlemskapsvurderingRiver(
    rapidsConnection: RapidsConnection,
    private val transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
    private val versjonAvKode: String = "test",
) : River.PacketListener {
    private val behovKey = "Medlemskapsvurdering"

    init {

        River(rapidsConnection)
            .apply {
                precondition {
                    it.requireValue("@event_name", "behov")
                    it.requireAllOrAny("@behov", listOf(behovKey))
                    it.forbid("@løsning")
                }
                validate {
                    it.requireKey("fødselsnummer")
                    it.require("Medlemskapsvurdering.skjæringstidspunkt", JsonNode::asLocalDate)
                    it.requireKey("Medlemskapsvurdering.arbeidssituasjon")
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
        val skjæringstidspunkt = packet["Medlemskapsvurdering.skjæringstidspunkt"].asLocalDate()
        val arbeidssituasjon = Arbeidssituasjon.valueOf(packet["Medlemskapsvurdering.arbeidssituasjon"].asString())

        medMdc(
            MdcKey.IDENTITETSNUMMER to fødselsnummer,
        ) {
            loggInfo("Mottatt behov for $behovKey", "skjæringstidspunkt" to skjæringstidspunkt.toString())

            val vurderMedlemskapResultat =
                transaksjonProvider.transaksjon { kontekst ->
                    MedlemskapService(kontekst, versjonAvKode).vurderMedlemskap(
                        fødselsnummer = fødselsnummer,
                        skjæringstidspunkt = skjæringstidspunkt,
                        arbeidssituasjon = arbeidssituasjon,
                    )
                }

            // Publisering skjer først etter at transaksjonen er commitet — vi forteller aldri
            // omverdenen om en vurdering vi ikke har lagret.
            when (vurderMedlemskapResultat) {
                is VurderMedlemskapResultat.HarVurdering -> {
                    packet["@løsning"] =
                        mapOf(
                            "Medlemskapsvurdering" to
                                mapOf(
                                    "id" to vurderMedlemskapResultat.medlemskapsvurderingId.toString(),
                                ),
                        )
                    loggInfo(
                        "Har vurdering. Publiserer løsning",
                        "medlemskapsvurderingId" to vurderMedlemskapResultat.medlemskapsvurderingId.toString(),
                        "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                        "løsning" to packet.toJson(),
                    )
                    context.publish(packet.toJson())
                }

                is VurderMedlemskapResultat.TrengerArbeidsforhold -> {
                    val utgåendeBehov =
                        JsonMessage.newNeed(
                            behov = listOf("ArbeidsforholdV2"),
                            map =
                                mapOf(
                                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                                    "fødselsnummer" to fødselsnummer,
                                    "opprinneligBehov" to jacksonObjectMapper().readTree(packet.toJson()),
                                ),
                        )
                    loggInfo(
                        "Trenger arbeidsforhold. Publiserer nytt behov",
                        "behov" to utgåendeBehov.toJson(),
                        "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                    )
                    context.publish(utgåendeBehov.toJson())
                }
            }
        }
    }
}
