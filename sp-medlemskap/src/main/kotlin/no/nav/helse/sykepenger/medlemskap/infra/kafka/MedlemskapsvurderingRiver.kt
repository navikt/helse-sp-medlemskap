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
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.TransaksjonProvider
import no.nav.helse.til
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.medMdc
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import java.time.Duration

/**
 * Oppsett for å vente på en forhåndsvurdering fra medlemskapstjenesten før den spørres direkte.
 * Medlemskapstjenesten forhåndsvurderer bare søknader fra arbeidstakere, så for andre
 * yrkesaktiviteter spør vi direkte med en gang.
 */
internal class VentPåForhåndsvurdering(
    private val ventetid: Duration,
    private val klokke: Clock = Clock.systemUTC(),
) {
    fun frist() = klokke.instant().plus(ventetid)

    fun kanForhåndsvurderes(yrkesaktivitetstype: String?) = yrkesaktivitetstype == "ARBEIDSTAKER"
}

/**
 * Løser behovet `Medlemskapsvurdering` fra Spleis med id-en til medlemskapsvurderingen for
 * skjæringstidspunktet. Finnes det ingen vurdering, startes en prøving og medlemskapsopplysninger
 * innhentes først, se [GrunnlagForAutomatiskMedlemskapsvurderingRiver].
 *
 * Er [ventPåForhåndsvurdering] satt og behovet gjelder en arbeidstaker, venter prøvingen først på en
 * forhåndsvurdering for skjæringstidspunktet, se [VentendeBehovjobb].
 */
internal class MedlemskapsvurderingRiver(
    rapidsConnection: RapidsConnection,
    private val transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
    private val versjonAvKode: String = "test",
    private val ventPåForhåndsvurdering: VentPåForhåndsvurdering? = null,
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
                    it.requireKey("fødselsnummer", "vedtaksperiodeId")
                    it.require("$behovKey.skjæringstidspunkt", JsonNode::asLocalDate)
                    it.require("$behovKey.medlemskapPeriodeFom", JsonNode::asLocalDate)
                    it.require("$behovKey.medlemskapPeriodeTom", JsonNode::asLocalDate)
                    it.interestedIn("yrkesaktivitetstype")
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
        val skjæringstidspunkt = packet["$behovKey.skjæringstidspunkt"].asLocalDate()
        val oppslagsperiode = packet["$behovKey.medlemskapPeriodeFom"].asLocalDate() til packet["$behovKey.medlemskapPeriodeTom"].asLocalDate()

        medMdc(
            MdcKey.IDENTITETSNUMMER to fødselsnummer,
        ) {
            loggInfo("Mottatt behov for $behovKey", "skjæringstidspunkt" to skjæringstidspunkt.toString())

            val forhåndsvurdering = forhåndsvurderingsforespørsel(packet)
            val resultat =
                transaksjonProvider.transaksjon { kontekst ->
                    MedlemskapService(kontekst, versjonAvKode).vurderMedlemskap(
                        fødselsnummer = fødselsnummer,
                        skjæringstidspunkt = skjæringstidspunkt,
                        oppslagsperiode = oppslagsperiode,
                        forhåndsvurdering = forhåndsvurdering,
                    )
                }

            // Publisering skjer først etter at transaksjonen er commitet — vi forteller aldri
            // omverdenen om en vurdering vi ikke har lagret.
            when (resultat) {
                is VurderMedlemskapResultat.HarVurdering -> {
                    packet["@løsning"] = mapOf(behovKey to mapOf("id" to resultat.medlemskapsvurderingId.toString()))
                    loggInfo(
                        "Har vurdering. Publiserer løsning",
                        "medlemskapsvurderingId" to resultat.medlemskapsvurderingId.toString(),
                        "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                        "løsning" to packet.toJson(),
                    )
                    context.publish(packet.toJson())
                }

                is VurderMedlemskapResultat.TrengerMedlemskapsopplysninger -> {
                    val utgåendeBehov =
                        Medlemskapsbehov.nytt(
                            fødselsnummer = fødselsnummer,
                            vedtaksperiodeId = packet["vedtaksperiodeId"].asString(),
                            skjæringstidspunkt = skjæringstidspunkt,
                            medlemskapsprøvingId = resultat.medlemskapsprøvingId,
                            oppslagsperiode = resultat.oppslagsperiode,
                            ekstra = mapOf("opprinneligBehov" to jacksonObjectMapper().readTree(packet.toJson())),
                        )
                    loggInfo(
                        "Trenger medlemskapsopplysninger. Publiserer nytt behov",
                        "behov" to utgåendeBehov.toJson(),
                        "medlemskapsprøvingId" to resultat.medlemskapsprøvingId.toString(),
                        "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                    )
                    context.publish(utgåendeBehov.toJson())
                }

                is VurderMedlemskapResultat.VenterPåForhåndsvurdering ->
                    loggInfo(
                        "Venter på forhåndsvurdering. Svarer på behovet senere",
                        "medlemskapsprøvingId" to resultat.medlemskapsprøvingId.toString(),
                        "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                    )
            }
        }
    }

    private fun forhåndsvurderingsforespørsel(packet: JsonMessage): MedlemskapService.Forhåndsvurderingsforespørsel? {
        val vent = ventPåForhåndsvurdering ?: return null
        if (!vent.kanForhåndsvurderes(packet["yrkesaktivitetstype"].takeIf { it.isString }?.asString())) return null
        return MedlemskapService.Forhåndsvurderingsforespørsel(
            vedtaksperiodeId = packet["vedtaksperiodeId"].asString(),
            opprinneligBehov = packet.toJson(),
            frist = vent.frist(),
        )
    }
}
