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
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.TransaksjonProvider
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.medMdc
import tools.jackson.databind.JsonNode

/**
 * Tar imot arbeidsforhold for en medlemskapsprøving som er startet på nytt via REST-APIet, se
 * [no.nav.helse.sykepenger.medlemskap.infra.rest.PostNyAutomatiskVilkårsvurderingBehandler].
 * Endringen meldes til Spleis og saksbehandlerhandlingen til Spesialist gjennom outboxen, ikke som løsning på et behov.
 */
internal class GrunnlagForNyArbeidstakerMedlemskapsvurderingRiver(
    rapidsConnection: RapidsConnection,
    private val transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
    private val versjonAvKode: String = "test",
) : River.PacketListener {
    private val behovKey = "ArbeidsforholdV2"

    init {
        River(rapidsConnection)
            .apply {
                precondition {
                    it.requireValue("@event_name", "behov")
                    it.requireAllOrAny("@behov", listOf(behovKey))
                    it.requireValue("@final", true)
                    it.requireKey("fødselsnummer")
                    it.requireKey("medlemskapsprøvingId")
                    it.requireKey("saksbehandlerIdent")
                    it.forbid("opprinneligBehov")
                    it.requireKey("@løsning")
                }

                validate {
                    it.require("skjæringstidspunkt", JsonNode::asLocalDate)
                    it.requireArray("@løsning.$behovKey") {
                        requireKey("orgnummer")
                        requireAny("type", listOf("FORENKLET_OPPGJØRSORDNING", "FRILANSER", "MARITIMT", "ORDINÆRT"))
                        require("ansattSiden", JsonNode::asLocalDate)
                        interestedIn("ansattTil", JsonNode::asLocalDate)
                    }
                }
            }.register(this)
    }

    override fun onPacket(
        packet: JsonMessage,
        context: MessageContext,
        metadata: MessageMetadata,
        meterRegistry: MeterRegistry,
    ) {
        val arbeidsforhold = packet["@løsning.$behovKey"].tilArbeidsforhold()
        val skjæringstidspunkt = packet["skjæringstidspunkt"].asLocalDate()
        val fødselsnummer = packet["fødselsnummer"].asString()
        val medlemskapsprøvingId = MedlemskapsprøvingId(packet["medlemskapsprøvingId"].asUUID())
        val saksbehandlerIdent = packet["saksbehandlerIdent"].asString()

        medMdc(
            MdcKey.IDENTITETSNUMMER to fødselsnummer,
        ) {
            loggInfo(
                "Mottatt løsning på behov for $behovKey til ny medlemskapsvurdering",
                "antallArbeidsforhold" to arbeidsforhold.size.toString(),
                "medlemskapsprøvingId" to medlemskapsprøvingId.toString(),
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            transaksjonProvider.transaksjon { kontekst ->
                MedlemskapService(kontekst, versjonAvKode).behandleGrunnlagForNyArbeidstakerMedlemskapsvurdering(
                    arbeidsforhold = arbeidsforhold,
                    fødselsnummer = fødselsnummer,
                    skjæringstidspunkt = skjæringstidspunkt,
                    medlemskapsprøvingId = medlemskapsprøvingId,
                    saksbehandlerIdent = saksbehandlerIdent,
                )
            }
        }
    }
}
