package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers.asLocalDate
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageProblems
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import no.nav.helse.sykepenger.medlemskap.application.MedlemskapService
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.infra.kafka.Medlemskapsbehov.aarsaker
import no.nav.helse.sykepenger.medlemskap.infra.kafka.Medlemskapsbehov.løsning
import no.nav.helse.sykepenger.medlemskap.infra.kafka.Medlemskapsbehov.medlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.infra.kafka.Medlemskapsbehov.medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.TransaksjonProvider
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggError
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.loggWarn
import no.nav.sykepenger.libs.logging.medMdc
import tools.jackson.databind.node.ObjectNode

/**
 * Tar imot medlemskapsopplysninger for en prøving startet av [MedlemskapsvurderingRiver], og svarer
 * på det opprinnelige `Medlemskapsvurdering`-behovet når vurderingen er lagret.
 */
internal class GrunnlagForAutomatiskMedlemskapsvurderingRiver(
    rapidsConnection: RapidsConnection,
    private val transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
    private val versjonAvKode: String = "test",
) : River.PacketListener {
    init {
        River(rapidsConnection)
            .løsning { it.requireKey("opprinneligBehov") }
            .register(this)
    }

    override fun onPacket(
        packet: JsonMessage,
        context: MessageContext,
        metadata: MessageMetadata,
        meterRegistry: MeterRegistry,
    ) {
        val svar = packet.medlemskapsstatus()
        val skjæringstidspunkt = packet["skjæringstidspunkt"].asLocalDate()
        val fødselsnummer = packet["fødselsnummer"].asString()
        val medlemskapsprøvingId = packet.medlemskapsprøvingId()

        medMdc(
            MdcKey.IDENTITETSNUMMER to fødselsnummer,
        ) {
            loggInfo(
                "Mottatt løsning på behov for ${Medlemskapsbehov.NAVN}",
                "svar" to svar.name,
                "medlemskapsprøvingId" to medlemskapsprøvingId.toString(),
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            val resultat =
                transaksjonProvider.transaksjon { kontekst ->
                    MedlemskapService(kontekst, versjonAvKode).behandleGrunnlagForAutomatiskMedlemskapsvurdering(
                        svar = svar,
                        fødselsnummer = fødselsnummer,
                        skjæringstidspunkt = skjæringstidspunkt,
                        medlemskapsprøvingId = medlemskapsprøvingId,
                        aarsaker = packet.aarsaker(),
                    )
                }
            // Publisering skjer først etter at transaksjonen er commitet — vi forteller aldri
            // omverdenen om en vurdering vi ikke har lagret.
            when (resultat) {
                MedlemskapService.BehandleGrunnlagResultat.AlleredeVurdert ->
                    loggWarn("Allerede vurdert. Ingen ny vurdering foretatt", "skjæringstidspunkt" to skjæringstidspunkt.toString())

                MedlemskapService.BehandleGrunnlagResultat.IngenPrøvingFunnet ->
                    loggWarn("Ingen prøving funnet", "skjæringstidspunkt" to skjæringstidspunkt.toString())

                is MedlemskapService.BehandleGrunnlagResultat.NyVurderingForetatt -> {
                    val opprinneligBehov = packet["opprinneligBehov"] as ObjectNode
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
                    context.publish(løsning)
                }
            }
        }
    }

    override fun onError(
        problems: MessageProblems,
        context: MessageContext,
        metadata: MessageMetadata,
    ) {
        loggError("Ugyldig løsning på behov for ${Medlemskapsbehov.NAVN}", "feil" to problems.toString())
    }
}
