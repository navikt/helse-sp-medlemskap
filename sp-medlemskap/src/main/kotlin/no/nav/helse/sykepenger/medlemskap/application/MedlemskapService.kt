package no.nav.helse.sykepenger.medlemskap.application

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import no.nav.helse.Periode
import no.nav.helse.sykepenger.medlemskap.application.VurderMedlemskapResultat.HarVurdering
import no.nav.helse.sykepenger.medlemskap.application.VurderMedlemskapResultat.TrengerMedlemskapsopplysninger
import no.nav.helse.sykepenger.medlemskap.application.VurderMedlemskapResultat.VenterPåForhåndsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.Forhåndsvurdering.Companion.gjeldendeFor
import no.nav.helse.sykepenger.medlemskap.domain.Grunnlagsbehov
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsprøving
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Årsak
import no.nav.helse.til
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggError
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.loggWarn
import no.nav.sykepenger.libs.logging.medMdc
import java.time.Instant
import java.time.LocalDate

internal class MedlemskapService(
    kontekst: Transaksjonskontekst,
    private val versjonAvKode: String = "test",
) {
    private val medlemskapsvurderingRepository = kontekst.medlemskapsvurderinger
    private val medlemskapsprøvingRepository = kontekst.medlemskapsprøvinger
    private val outbox = kontekst.outbox
    private val forhåndsvurderingRepository = kontekst.forhåndsvurderinger
    private val ventendeBehovRepository = kontekst.ventendeBehov

    /**
     * Opplysningene som trengs for å vente på en forhåndsvurdering i stedet for å spørre
     * medlemskapstjenesten med en gang. Uten forespørsel spør vi alltid direkte.
     */
    data class Forhåndsvurderingsforespørsel(
        val vedtaksperiodeId: String,
        val opprinneligBehov: String,
        val frist: Instant,
    )

    fun vurderMedlemskap(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
        oppslagsperiode: Periode,
        forhåndsvurdering: Forhåndsvurderingsforespørsel? = null,
    ): VurderMedlemskapResultat =
        medMdc(
            MdcKey.IDENTITETSNUMMER to fødselsnummer,
        ) {
            medlemskapsvurderingRepository.gjeldende(fødselsnummer, skjæringstidspunkt)?.let { vurdering ->
                loggInfo(
                    "Har allerede medlemskapsvurdering",
                    "medlemskapsvurderingId" to vurdering.id.toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
                return@medMdc HarVurdering(fødselsnummer, skjæringstidspunkt, vurdering.id)
            }

            medlemskapsprøvingRepository.finnSiste(fødselsnummer, skjæringstidspunkt)?.takeUnless { it.erAvsluttet }?.let {
                if (it.venterPåForhåndsvurdering) return@medMdc it.venterFortsattPåForhåndsvurdering(forhåndsvurdering)
                loggInfo(
                    "Medlemskapsprøving pågår allerede. Etterspør medlemskapsopplysninger på nytt",
                    "medlemskapsprøvingId" to it.id.toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
                return@medMdc it.trengerMedlemskapsopplysninger()
            }

            if (forhåndsvurdering != null) {
                return@medMdc startPrøvingMedForhåndsvurdering(fødselsnummer, skjæringstidspunkt, oppslagsperiode, forhåndsvurdering)
            }

            val prøving = Medlemskapsprøving.start(fødselsnummer, skjæringstidspunkt, oppslagsperiode)
            medlemskapsprøvingRepository.lagre(prøving)
            loggInfo(
                "Startet medlemskapsprøving. Venter på medlemskapsopplysninger",
                "medlemskapsprøvingId" to prøving.id.toString(),
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            prøving.trengerMedlemskapsopplysninger()
        }

    private fun startPrøvingMedForhåndsvurdering(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
        oppslagsperiode: Periode,
        forespørsel: Forhåndsvurderingsforespørsel,
    ): VurderMedlemskapResultat {
        val prøving = Medlemskapsprøving.start(fødselsnummer, skjæringstidspunkt, oppslagsperiode, Grunnlagsbehov.Forhåndsvurdering)
        val forhåndsvurdering = forhåndsvurderingRepository.finn(fødselsnummer).gjeldendeFor(skjæringstidspunkt)
        if (forhåndsvurdering != null) {
            val vurdering = prøving.motta(forhåndsvurdering, versjonAvKode)
            medlemskapsprøvingRepository.lagre(prøving)
            medlemskapsvurderingRepository.lagre(vurdering)
            loggInfo(
                "Medlemskapsprøving fullført med forhåndsvurdering som allerede var mottatt",
                "medlemskapsprøvingId" to prøving.id.toString(),
                "medlemskapsvurderingId" to vurdering.id.toString(),
                "forhåndsvurderingId" to forhåndsvurdering.id.toString(),
                "søknadId" to forhåndsvurdering.søknadId.toString(),
                "status" to vurdering.status.name,
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            return HarVurdering(fødselsnummer, skjæringstidspunkt, vurdering.id)
        }

        medlemskapsprøvingRepository.lagre(prøving)
        ventendeBehovRepository.lagre(
            VentendeBehov(
                medlemskapsprøvingId = prøving.id,
                fødselsnummer = fødselsnummer,
                vedtaksperiodeId = forespørsel.vedtaksperiodeId,
                opprinneligBehov = forespørsel.opprinneligBehov,
                frist = forespørsel.frist,
            ),
        )
        loggInfo(
            "Startet medlemskapsprøving. Venter på forhåndsvurdering",
            "medlemskapsprøvingId" to prøving.id.toString(),
            "frist" to forespørsel.frist.toString(),
            "skjæringstidspunkt" to skjæringstidspunkt.toString(),
        )
        return VenterPåForhåndsvurdering(fødselsnummer, skjæringstidspunkt, prøving.id)
    }

    /** Et nytt behov fra Spleis erstatter det som venter, så det er det nyeste behovet som til slutt besvares. */
    private fun Medlemskapsprøving.venterFortsattPåForhåndsvurdering(forespørsel: Forhåndsvurderingsforespørsel?): VurderMedlemskapResultat {
        val ventende = ventendeBehovRepository.finn(id)
        if (ventende != null && forespørsel != null) {
            ventendeBehovRepository.lagre(
                ventende.copy(vedtaksperiodeId = forespørsel.vedtaksperiodeId, opprinneligBehov = forespørsel.opprinneligBehov),
            )
        }
        loggInfo(
            "Medlemskapsprøving venter allerede på forhåndsvurdering",
            "medlemskapsprøvingId" to id.toString(),
            "skjæringstidspunkt" to skjæringstidspunkt.toString(),
        )
        return VenterPåForhåndsvurdering(fødselsnummer, skjæringstidspunkt, id)
    }

    /**
     * Ser om prøvingen bak et ventende behov har fått en forhåndsvurdering. Har den det, lagres
     * vurderingen. Har fristen gått ut, går prøvingen over til å spørre medlemskapstjenesten direkte.
     * I begge tilfeller fjernes det ventende behovet, og kalleren må svare på det opprinnelige behovet
     * eller sende behovet `Medlemskap`.
     *
     * Jobben henter id-ene før den behandler dem, hver i sin transaksjon. Er behovet borte når det
     * skal behandles, for eksempel fordi personen er slettet, er det ikke lenger aktuelt.
     */
    fun behandleVentendeBehov(
        medlemskapsprøvingId: MedlemskapsprøvingId,
        nå: Instant,
    ): BehandleVentendeBehovResultat {
        val ventende = ventendeBehovRepository.finn(medlemskapsprøvingId) ?: return BehandleVentendeBehovResultat.IkkeLengerAktuelt
        return medMdc(
            MdcKey.IDENTITETSNUMMER to ventende.fødselsnummer,
        ) {
            val prøving = medlemskapsprøvingRepository.finn(medlemskapsprøvingId)
            if (prøving == null || !prøving.venterPåForhåndsvurdering) {
                loggWarn(
                    "Ventende behov hører til en prøving som ikke venter på forhåndsvurdering. Fjerner behovet",
                    "medlemskapsprøvingId" to medlemskapsprøvingId.toString(),
                )
                ventendeBehovRepository.fjern(medlemskapsprøvingId)
                return@medMdc BehandleVentendeBehovResultat.IkkeLengerAktuelt
            }

            forhåndsvurderingRepository.finn(ventende.fødselsnummer).gjeldendeFor(prøving.skjæringstidspunkt)?.let { forhåndsvurdering ->
                val vurdering = prøving.motta(forhåndsvurdering, versjonAvKode)
                medlemskapsprøvingRepository.lagre(prøving)
                medlemskapsvurderingRepository.lagre(vurdering)
                ventendeBehovRepository.fjern(medlemskapsprøvingId)
                loggInfo(
                    "Medlemskapsprøving fullført med forhåndsvurdering",
                    "medlemskapsprøvingId" to prøving.id.toString(),
                    "medlemskapsvurderingId" to vurdering.id.toString(),
                    "forhåndsvurderingId" to forhåndsvurdering.id.toString(),
                    "søknadId" to forhåndsvurdering.søknadId.toString(),
                    "status" to vurdering.status.name,
                    "skjæringstidspunkt" to prøving.skjæringstidspunkt.toString(),
                )
                return@medMdc BehandleVentendeBehovResultat.NyVurderingForetatt(ventende, vurdering.id)
            }

            if (nå < ventende.frist) return@medMdc BehandleVentendeBehovResultat.VenterFortsatt

            prøving.slåOppIMedlemskapstjenesten()
            medlemskapsprøvingRepository.lagre(prøving)
            ventendeBehovRepository.fjern(medlemskapsprøvingId)
            loggInfo(
                "Fikk ikke forhåndsvurdering innen fristen. Spør medlemskapstjenesten direkte",
                "medlemskapsprøvingId" to prøving.id.toString(),
                "frist" to ventende.frist.toString(),
                "skjæringstidspunkt" to prøving.skjæringstidspunkt.toString(),
            )
            BehandleVentendeBehovResultat.TrengerMedlemskapsopplysninger(ventende, prøving.trengerMedlemskapsopplysninger())
        }
    }

    sealed class BehandleVentendeBehovResultat {
        /** Verken forhåndsvurdering eller utløpt frist ennå. */
        data object VenterFortsatt : BehandleVentendeBehovResultat()

        /** Behovet er borte, eller prøvingen venter ikke lenger. Det er ingenting å svare på. */
        data object IkkeLengerAktuelt : BehandleVentendeBehovResultat()

        data class NyVurderingForetatt(
            val behov: VentendeBehov,
            val medlemskapsvurderingId: MedlemskapsvurderingId,
        ) : BehandleVentendeBehovResultat()

        data class TrengerMedlemskapsopplysninger(
            val behov: VentendeBehov,
            val opplysninger: VurderMedlemskapResultat.TrengerMedlemskapsopplysninger,
        ) : BehandleVentendeBehovResultat()
    }

    fun behandleGrunnlagForAutomatiskMedlemskapsvurdering(
        svar: Medlemskapsstatus,
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
        medlemskapsprøvingId: MedlemskapsprøvingId,
        aarsaker: List<Årsak>? = null,
    ): BehandleGrunnlagResultat =
        medMdc(
            MdcKey.IDENTITETSNUMMER to fødselsnummer,
        ) {
            val prøving =
                medlemskapsprøvingRepository
                    .finnSiste(fødselsnummer, skjæringstidspunkt)
                    ?.takeIf { it.id == medlemskapsprøvingId }
            avvisGrunnlag(prøving, medlemskapsprøvingId, skjæringstidspunkt)?.let { return@medMdc it }
            checkNotNull(prøving)

            val vurdering = prøving.motta(svar, versjonAvKode, aarsaker)
            medlemskapsvurderingRepository.lagre(vurdering)
            medlemskapsprøvingRepository.lagre(prøving)
            loggInfo(
                "Medlemskapsprøving fullført",
                "medlemskapsprøvingId" to prøving.id.toString(),
                "medlemskapsvurderingId" to vurdering.id.toString(),
                "status" to vurdering.status.name,
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            BehandleGrunnlagResultat.NyVurderingForetatt(fødselsnummer, skjæringstidspunkt, vurdering.id)
        }

    /**
     * Starter en ny automatisk medlemskapsprøving for et skjæringstidspunkt som allerede er vurdert.
     * Det er kallerens ansvar å be om medlemskapsopplysninger for prøvingen, og å ta med [saksbehandlerIdent]
     * videre til [behandleGrunnlagForNyMedlemskapsvurdering], som melder saksbehandlerhandlingen til
     * Spesialist og endringen til Spleis via outboxen når den nye vurderingen er klar.
     *
     * Medlemskapstjenesten spørres om samme periode som sist vi prøvde skjæringstidspunktet. Er
     * skjæringstidspunktet aldri prøvd her (vurderingen er manuell eller overført fra Spleis/Infotrygd),
     * spørres det om skjæringstidspunktet alene.
     */
    fun vurderMedlemskapPåNytt(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ): VurderMedlemskapPåNyttResultat =
        medMdc(
            MdcKey.IDENTITETSNUMMER to fødselsnummer,
        ) {
            medlemskapsvurderingRepository.gjeldende(fødselsnummer, skjæringstidspunkt)
                ?: return@medMdc VurderMedlemskapPåNyttResultat.IngenVurderingFunnet

            val forrigePrøving = medlemskapsprøvingRepository.finnSiste(fødselsnummer, skjæringstidspunkt)
            if (forrigePrøving != null && !forrigePrøving.erAvsluttet) {
                loggInfo(
                    "Kan ikke vurdere medlemskap på nytt, medlemskapsprøving pågår allerede",
                    "medlemskapsprøvingId" to forrigePrøving.id.toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
                return@medMdc VurderMedlemskapPåNyttResultat.PrøvingPågår
            }

            val oppslagsperiode = forrigePrøving?.oppslagsperiode ?: (skjæringstidspunkt til skjæringstidspunkt)
            val prøving = Medlemskapsprøving.start(fødselsnummer, skjæringstidspunkt, oppslagsperiode)
            medlemskapsprøvingRepository.lagre(prøving)
            loggInfo(
                "Startet ny medlemskapsprøving. Venter på medlemskapsopplysninger",
                "medlemskapsprøvingId" to prøving.id.toString(),
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            VurderMedlemskapPåNyttResultat.VenterPåGrunnlag(prøving.id, prøving.oppslagsperiode)
        }

    sealed class VurderMedlemskapPåNyttResultat {
        data class VenterPåGrunnlag(
            val medlemskapsprøvingId: MedlemskapsprøvingId,
            val oppslagsperiode: Periode,
        ) : VurderMedlemskapPåNyttResultat()

        data object IngenVurderingFunnet : VurderMedlemskapPåNyttResultat()

        data object PrøvingPågår : VurderMedlemskapPåNyttResultat()
    }

    fun behandleGrunnlagForNyMedlemskapsvurdering(
        svar: Medlemskapsstatus,
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
        medlemskapsprøvingId: MedlemskapsprøvingId,
        saksbehandlerIdent: String,
        aarsaker: List<Årsak>? = null,
    ): BehandleGrunnlagResultat =
        medMdc(
            MdcKey.IDENTITETSNUMMER to fødselsnummer,
        ) {
            val prøving =
                medlemskapsprøvingRepository
                    .finnSiste(fødselsnummer, skjæringstidspunkt)
                    ?.takeIf { it.id == medlemskapsprøvingId }
            avvisGrunnlag(prøving, medlemskapsprøvingId, skjæringstidspunkt)?.let { return@medMdc it }
            checkNotNull(prøving)

            val vurdering = prøving.motta(svar, versjonAvKode, aarsaker)
            medlemskapsprøvingRepository.lagre(prøving)
            lagreOgMeldNyVurdering(vurdering, saksbehandlerIdent)
            loggInfo(
                "Ny medlemskapsprøving fullført",
                "medlemskapsprøvingId" to prøving.id.toString(),
                "medlemskapsvurderingId" to vurdering.id.toString(),
                "status" to vurdering.status.name,
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            BehandleGrunnlagResultat.NyVurderingForetatt(fødselsnummer, skjæringstidspunkt, vurdering.id)
        }

    /** Gir et resultat dersom grunnlaget ikke kan tas imot, ellers null. */
    private fun avvisGrunnlag(
        prøving: Medlemskapsprøving?,
        medlemskapsprøvingId: MedlemskapsprøvingId,
        skjæringstidspunkt: LocalDate,
    ): BehandleGrunnlagResultat? {
        if (prøving == null) {
            loggError(
                "Mottatt medlemskapsopplysninger, men fant ikke prøvingen som siste prøving",
                "medlemskapsprøvingId" to medlemskapsprøvingId.toString(),
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            return BehandleGrunnlagResultat.IngenPrøvingFunnet
        }
        if (prøving.erAvsluttet) {
            loggInfo(
                "Mottatt medlemskapsopplysninger, men prøving er allerede avsluttet",
                "medlemskapsprøvingId" to prøving.id.toString(),
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            return BehandleGrunnlagResultat.AlleredeVurdert
        }
        if (prøving.venterPåForhåndsvurdering) {
            loggError(
                "Mottatt medlemskapsopplysninger, men prøvingen venter på forhåndsvurdering",
                "medlemskapsprøvingId" to prøving.id.toString(),
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            return BehandleGrunnlagResultat.IngenPrøvingFunnet
        }
        return null
    }

    private fun Medlemskapsprøving.trengerMedlemskapsopplysninger() = TrengerMedlemskapsopplysninger(fødselsnummer, skjæringstidspunkt, id, oppslagsperiode)

    private fun lagreOgMeldNyVurdering(
        vurdering: Medlemskapsvurdering,
        saksbehandlerIdent: String,
    ) {
        medlemskapsvurderingRepository.lagre(vurdering)
        outbox.leggTil(
            OutboxKonvolutt.ny(
                melding =
                    OutboxMelding.Saksbehandlerhandling(
                        skjæringstidspunkt = vurdering.skjæringstidspunkt,
                        saksbehandlerIdent = saksbehandlerIdent,
                        handlingId = vurdering.id.value,
                        handlingType = HandlingType.NY_AUTOMATISK_MEDLEMSKAPSVURDERING,
                        kreverTotrinnsvurdering = false,
                        tidspunkt = Instant.now(),
                    ),
                identitetsnummer = Identitetsnummer(vurdering.fødselsnummer),
            ),
        )
        outbox.leggTil(
            OutboxKonvolutt.ny(
                melding =
                    OutboxMelding.MedlemskapsvurderingEndret(
                        skjæringstidspunkt = vurdering.skjæringstidspunkt,
                        medlemskapsvurderingId = vurdering.id.value,
                        manuellVurdering = false,
                    ),
                identitetsnummer = Identitetsnummer(vurdering.fødselsnummer),
            ),
        )
    }

    sealed class BehandleGrunnlagResultat {
        data class NyVurderingForetatt(
            val fødselsnummer: String,
            val skjæringstidspunkt: LocalDate,
            val medlemskapsvurderingId: MedlemskapsvurderingId,
        ) : BehandleGrunnlagResultat()

        data object AlleredeVurdert : BehandleGrunnlagResultat()

        data object IngenPrøvingFunnet : BehandleGrunnlagResultat()
    }
}
