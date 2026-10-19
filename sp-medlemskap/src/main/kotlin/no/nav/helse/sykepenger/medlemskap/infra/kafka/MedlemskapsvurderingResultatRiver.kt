package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.tilMedlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.TransaksjonProvider
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggError
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.medMdc

internal open class MedlemskapsvurderingResultatRiver(
    rapidsConnection: RapidsConnection,
    private val transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
    private val spleisClient: ISpleisClient,
) : River.PacketListener {
    private val behovnavn = "MedlemskapsvurderingResultat"
    private val idFelt = "MedlemskapsvurderingResultat.medlemskapsvurderingId"

    init {
        River(rapidsConnection)
            .apply {
                precondition {
                    it.requireValue("@event_name", "behov")
                    it.requireAllOrAny("@behov", listOf(behovnavn))
                    it.forbid("@løsning")
                }
                validate {
                    it.requireKey("fødselsnummer")
                    it.requireKey(idFelt)
                }
            }.register(this)
    }

    override fun onPacket(
        packet: JsonMessage,
        context: MessageContext,
        metadata: MessageMetadata,
        meterRegistry: MeterRegistry,
    ) {
        val medlemskapsvurderingId = MedlemskapsvurderingId(packet[idFelt].asUUID())
        val fødselsnummer = packet["fødselsnummer"].asString()

        medMdc(
            MdcKey.IDENTITETSNUMMER to fødselsnummer,
        ) {
            loggInfo("Mottatt behov for $behovnavn", "medlemskapsvurderingId" to medlemskapsvurderingId.toString())

            try {
                val medlemskapsvurdering =
                    transaksjonProvider.transaksjon {
                        it.medlemskapsvurderinger.finn(medlemskapsvurderingId)
                    }

                val status =
                    medlemskapsvurdering?.status
                        ?: hentVurderingFraSpleis(fødselsnummer, medlemskapsvurderingId)
                        ?: error("Fant ikke vurdering med id $medlemskapsvurderingId")

                packet["@løsning"] = mapOf(behovnavn to mapOf("svar" to status.tilSvar(), "ok" to status.erOk))
                loggInfo(
                    "Publiserer løsning",
                    "løsning" to packet.toJson(),
                )
                context.publish(packet.toJson())
            } catch (ex: Exception) {
                loggError("Feil under håndtering av behov $behovnavn", ex, "medlemskapsvurderingId" to medlemskapsvurderingId.toString())
            }
        }
    }

    private fun hentVurderingFraSpleis(
        fødselsnummer: String,
        medlemskapsvurderingId: MedlemskapsvurderingId,
    ): Medlemskapsstatus? =
        spleisClient
            .hentMedlemskapsvurderinger(fødselsnummer = fødselsnummer)
            .find { it.medlemskapsvurderingId == medlemskapsvurderingId }
            ?.tilMedlemskapsvurdering(fødselsnummer)
            ?.status

    // Samme verdier som sparkel-medlemskap svarer med, slik at Spleis kan tolke begge likt.
    private fun Medlemskapsstatus.tilSvar() =
        when (this) {
            Medlemskapsstatus.Ja -> "JA"
            Medlemskapsstatus.Nei -> "NEI"
            Medlemskapsstatus.Uavklart -> "UAVKLART"
            Medlemskapsstatus.UavklartMedBrukerspørsmål -> "UAVKLART_MED_BRUKERSPORSMAAL"
        }
}
