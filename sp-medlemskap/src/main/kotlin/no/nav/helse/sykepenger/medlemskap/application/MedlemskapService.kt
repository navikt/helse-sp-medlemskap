package no.nav.helse.sykepenger.medlemskap.application

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import no.nav.helse.Periode
import no.nav.helse.sykepenger.medlemskap.application.VurderMedlemskapResultat.HarVurdering
import no.nav.helse.sykepenger.medlemskap.application.VurderMedlemskapResultat.TrengerMedlemskapsopplysninger
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

    fun vurderMedlemskap(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
        oppslagsperiode: Periode,
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
                loggInfo(
                    "Medlemskapsprøving pågår allerede. Etterspør medlemskapsopplysninger på nytt",
                    "medlemskapsprøvingId" to it.id.toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
                return@medMdc it.trengerMedlemskapsopplysninger()
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
