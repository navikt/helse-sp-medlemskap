package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Vurderingskilde
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.infra.spleis.tilMedlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.TransaksjonProvider
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggError
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.loggWarn
import no.nav.sykepenger.libs.logging.medMdc

/**
 * Svarer på resultatet for en bestemt medlemskapsvurderingId. Kontrakt: én id gir alltid samme svar.
 * Spleis spør om id-en på vilkårsgrunnlaget ved hver beregning, også for forlengelser. Slå derfor
 * alltid opp med id, aldri gjeldende vurdering for skjæringstidspunktet. En ny vurdering skal ha ny id
 * og sendes som endret_medlemskapsvurdering, slik at Spleis bytter id og revurderer.
 *
 * Når sp-medlemskap selv har gjort vurderingen, sammenligner riveren den med statusen Spleis har på
 * vilkårsgrunnlaget, og logger om de er like eller ulike. Sammenligningen påvirker ikke svaret. Den er
 * midlertidig, og bruker kallet mot Spleis som tidligere bare ble gjort når id-en var ukjent her.
 */
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

                medlemskapsvurdering?.let { sammenlignMedSpleis(fødselsnummer, it) }
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

    private fun sammenlignMedSpleis(
        fødselsnummer: String,
        vurdering: Medlemskapsvurdering,
    ) {
        val kilde = vurdering.kilde.tilSammenligningskilde() ?: return
        val spleisstatus =
            try {
                spleisClient
                    .hentMedlemskapsvurderinger(fødselsnummer = fødselsnummer)
                    .find { it.medlemskapsvurderingId == vurdering.id }
                    ?.status()
            } catch (ex: Exception) {
                loggWarn(
                    "Klarte ikke sammenligne medlemskapsvurdering med Spleis",
                    ex,
                    "medlemskapsvurderingId" to vurdering.id.toString(),
                )
                return
            }

        val detaljer =
            arrayOf(
                "medlemskapsvurderingId" to vurdering.id.toString(),
                "skjæringstidspunkt" to vurdering.skjæringstidspunkt.toString(),
                "kilde" to kilde,
                "statusSpMedlemskap" to vurdering.status.tilSvar(),
                "statusSpleis" to spleisstatus?.tilSvar(),
            )
        when {
            spleisstatus == null -> loggWarn("Fant ikke medlemskapsvurderingen i Spleis for sammenligning", *detaljer)
            spleisstatus == vurdering.status -> loggInfo("Medlemskapsvurdering samsvarer med Spleis", *detaljer)
            else ->
                loggWarn(
                    "Medlemskapsvurdering avviker fra Spleis",
                    *detaljer,
                    "retning" to retning(sp = vurdering.status, spleis = spleisstatus),
                )
        }
    }

    // Overførte vurderinger er Spleis sine egne, så de er det ingen vits i å sammenligne.
    private fun Vurderingskilde.tilSammenligningskilde() =
        when (this) {
            is Vurderingskilde.Automatisk -> if (forhåndsvurdertSøknadId != null) "FORHANDSVURDERING" else "OPPSLAG"
            is Vurderingskilde.Saksbehandler -> "SAKSBEHANDLER"
            Vurderingskilde.OverførtFraSpleis, Vurderingskilde.OverførtFraInfotrygd -> null
        }

    private fun SpleisMedlemskapsvurdering.status() =
        when (this) {
            is SpleisMedlemskapsvurdering.Spleis -> status
            is SpleisMedlemskapsvurdering.Infotrygd -> Medlemskapsstatus.Ja
        }

    // Rangert fra mest til minst positiv for bruker
    private fun retning(
        sp: Medlemskapsstatus,
        spleis: Medlemskapsstatus,
    ): String {
        val rangering =
            listOf(
                Medlemskapsstatus.Ja,
                Medlemskapsstatus.Uavklart,
                Medlemskapsstatus.UavklartMedBrukerspørsmål,
                Medlemskapsstatus.Nei,
            )
        return if (rangering.indexOf(sp) < rangering.indexOf(spleis)) "SP_MEDLEMSKAP_MER_POSITIV" else "SP_MEDLEMSKAP_MER_NEGATIV"
    }

    // Samme verdier som sparkel-medlemskap svarer med, slik at Spleis kan tolke begge likt.
    private fun Medlemskapsstatus.tilSvar() =
        when (this) {
            Medlemskapsstatus.Ja -> "JA"
            Medlemskapsstatus.Nei -> "NEI"
            Medlemskapsstatus.Uavklart -> "UAVKLART"
            Medlemskapsstatus.UavklartMedBrukerspørsmål -> "UAVKLART_MED_BRUKERSPORSMAAL"
        }
}
