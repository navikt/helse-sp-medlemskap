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
import no.nav.helse.sykepenger.medlemskap.infra.kafka.Medlemskapsbehov.løsning
import no.nav.helse.sykepenger.medlemskap.infra.kafka.Medlemskapsbehov.medlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.infra.kafka.Medlemskapsbehov.medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.TransaksjonProvider
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.medMdc

/**
 * Tar imot medlemskapsopplysninger for en medlemskapsprøving som er startet på nytt via REST-APIet, se
 * [no.nav.helse.sykepenger.medlemskap.infra.rest.PostNyAutomatiskVilkårsvurderingBehandler].
 * Endringen meldes til Spleis og saksbehandlerhandlingen til Spesialist gjennom outboxen, ikke som løsning på et behov.
 */
internal class GrunnlagForNyMedlemskapsvurderingRiver(
    rapidsConnection: RapidsConnection,
    private val transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
    private val versjonAvKode: String = "test",
) : River.PacketListener {
    init {
        River(rapidsConnection)
            .løsning {
                it.requireKey("saksbehandlerIdent")
                it.forbid("opprinneligBehov")
            }.register(this)
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
        val saksbehandlerIdent = packet["saksbehandlerIdent"].asString()

        medMdc(
            MdcKey.IDENTITETSNUMMER to fødselsnummer,
        ) {
            loggInfo(
                "Mottatt løsning på behov for ${Medlemskapsbehov.NAVN} til ny medlemskapsvurdering",
                "svar" to svar.name,
                "medlemskapsprøvingId" to medlemskapsprøvingId.toString(),
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            transaksjonProvider.transaksjon { kontekst ->
                MedlemskapService(kontekst, versjonAvKode).behandleGrunnlagForNyMedlemskapsvurdering(
                    svar = svar,
                    fødselsnummer = fødselsnummer,
                    skjæringstidspunkt = skjæringstidspunkt,
                    medlemskapsprøvingId = medlemskapsprøvingId,
                    saksbehandlerIdent = saksbehandlerIdent,
                )
            }
        }
    }
}
