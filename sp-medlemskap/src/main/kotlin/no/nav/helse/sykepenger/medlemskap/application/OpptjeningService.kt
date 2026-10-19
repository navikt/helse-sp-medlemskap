package no.nav.helse.sykepenger.medlemskap.application

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import no.nav.helse.sykepenger.medlemskap.application.VurderMedlemskapResultat.HarVurdering
import no.nav.helse.sykepenger.medlemskap.application.VurderMedlemskapResultat.TrengerArbeidsforhold
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidsforhold
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidssituasjon
import no.nav.helse.sykepenger.medlemskap.domain.Kategori
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsgrunnlag
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsprøving
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
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
        arbeidssituasjon: Arbeidssituasjon,
    ): VurderMedlemskapResultat =
        medMdc(
            MdcKey.IDENTITETSNUMMER to fødselsnummer,
        ) {
            medlemskapsvurderingRepository
                .gjeldende(fødselsnummer, skjæringstidspunkt)
                ?.takeIf { it.kategori == arbeidssituasjon.kategori }
                ?.let { vurdering ->
                    loggInfo(
                        "Har allerede medlemskapsvurdering",
                        "medlemskapsvurderingId" to vurdering.id.toString(),
                        "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                    )
                    return@medMdc HarVurdering(fødselsnummer, skjæringstidspunkt, vurdering.id)
                }

            medlemskapsprøvingRepository.finnSiste(fødselsnummer, skjæringstidspunkt, arbeidssituasjon.kategori)?.takeUnless { it.erAvsluttet }?.let {
                loggInfo(
                    "Medlemskapsprøving pågår allerede. Etterspør grunnlaget på nytt",
                    "medlemskapsprøvingId" to it.id.toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
                return@medMdc TrengerArbeidsforhold(fødselsnummer, skjæringstidspunkt)
            }

            val (prøving, vurdering) =
                Medlemskapsprøving.start(
                    fødselsnummer,
                    skjæringstidspunkt,
                    arbeidssituasjon,
                    versjonAvKode,
                )
            medlemskapsprøvingRepository.lagre(prøving)

            if (vurdering == null) {
                loggInfo(
                    "Startet medlemskapsprøving. Venter på utestående behov",
                    "medlemskapsprøvingId" to prøving.id.toString(),
                    "uteståendeBehov" to prøving.uteståendeBehov.toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
                return@medMdc TrengerArbeidsforhold(fødselsnummer, skjæringstidspunkt)
            }

            medlemskapsvurderingRepository.lagre(vurdering)
            loggInfo(
                "Medlemskapsprøving fullført uten innhenting",
                "medlemskapsprøvingId" to prøving.id.toString(),
                "medlemskapsvurderingId" to vurdering.id.toString(),
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            HarVurdering(fødselsnummer, skjæringstidspunkt, vurdering.id)
        }

    private val Arbeidssituasjon.kategori: Kategori
        get() =
            when (this) {
                Arbeidssituasjon.Arbeidstaker -> Kategori.Arbeidstaker
                Arbeidssituasjon.SelvstendigNæringsdrivende -> Kategori.SelvstendigNæringsdrivende
            }

    fun behandleGrunnlagForAutomatiskArbeidstakerMedlemskapsvurdering(
        arbeidsforhold: List<Arbeidsforhold>,
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ): BehandleGrunnlagResultat =
        medMdc(
            MdcKey.IDENTITETSNUMMER to fødselsnummer,
        ) {
            val prøving = medlemskapsprøvingRepository.finnSiste(fødselsnummer, skjæringstidspunkt, Kategori.Arbeidstaker)

            if (prøving == null) {
                loggError("Mottatt grunnlag for medlemskap, men fant ingen prøving", "skjæringstidspunkt" to skjæringstidspunkt.toString())
                return@medMdc BehandleGrunnlagResultat.IngenPrøvingFunnet
            }

            if (prøving.erAvsluttet) {
                loggInfo(
                    "Mottatt grunnlag for medlemskap, men prøving er allerede avsluttet",
                    "medlemskapsprøvingId" to prøving.id.toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
                return@medMdc BehandleGrunnlagResultat.AlleredeVurdert
            }

            val vurdering = prøving.motta(Medlemskapsgrunnlag.Arbeidstaker(arbeidsforhold), versjonAvKode)
            medlemskapsvurderingRepository.lagre(vurdering)
            medlemskapsprøvingRepository.lagre(prøving)
            loggInfo(
                "Medlemskapsprøving fullført",
                "medlemskapsprøvingId" to prøving.id.toString(),
                "medlemskapsvurderingId" to vurdering.id.toString(),
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            BehandleGrunnlagResultat.NyVurderingForetatt(fødselsnummer, skjæringstidspunkt, vurdering.id)
        }

    /**
     * Starter en ny automatisk medlemskapsprøving for et skjæringstidspunkt som allerede er vurdert,
     * og melder saksbehandlerhandlingen til Spesialist og endringen til Spleis via outboxen når den nye vurderingen er klar.
     * Ved [VurderMedlemskapPåNyttResultat.VenterPåGrunnlag] er det kallerens ansvar å be om arbeidsforhold for prøvingen,
     * og å ta med [saksbehandlerIdent] videre til [behandleGrunnlagForNyArbeidstakerMedlemskapsvurdering].
     */
    fun vurderMedlemskapPåNytt(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
        saksbehandlerIdent: String,
    ): VurderMedlemskapPåNyttResultat =
        medMdc(
            MdcKey.IDENTITETSNUMMER to fødselsnummer,
        ) {
            val gjeldende =
                medlemskapsvurderingRepository.gjeldende(fødselsnummer, skjæringstidspunkt)
                    ?: return@medMdc VurderMedlemskapPåNyttResultat.IngenVurderingFunnet

            medlemskapsprøvingRepository.finnSiste(fødselsnummer, skjæringstidspunkt, gjeldende.kategori)?.takeUnless { it.erAvsluttet }?.let {
                loggInfo(
                    "Kan ikke vurdere medlemskap på nytt, medlemskapsprøving pågår allerede",
                    "medlemskapsprøvingId" to it.id.toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
                return@medMdc VurderMedlemskapPåNyttResultat.PrøvingPågår
            }

            val (prøving, vurdering) =
                Medlemskapsprøving.start(
                    fødselsnummer,
                    skjæringstidspunkt,
                    gjeldende.kategori.arbeidssituasjon,
                    versjonAvKode,
                )
            medlemskapsprøvingRepository.lagre(prøving)

            if (vurdering == null) {
                loggInfo(
                    "Startet ny medlemskapsprøving. Venter på arbeidsforhold",
                    "medlemskapsprøvingId" to prøving.id.toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
                return@medMdc VurderMedlemskapPåNyttResultat.VenterPåGrunnlag(prøving.id)
            }

            lagreOgMeldNyVurdering(vurdering, saksbehandlerIdent)
            loggInfo(
                "Medlemskap vurdert på nytt uten innhenting",
                "medlemskapsprøvingId" to prøving.id.toString(),
                "medlemskapsvurderingId" to vurdering.id.toString(),
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            VurderMedlemskapPåNyttResultat.NyVurderingForetatt(vurdering.id)
        }

    sealed class VurderMedlemskapPåNyttResultat {
        data class NyVurderingForetatt(
            val medlemskapsvurderingId: MedlemskapsvurderingId,
        ) : VurderMedlemskapPåNyttResultat()

        data class VenterPåGrunnlag(
            val medlemskapsprøvingId: MedlemskapsprøvingId,
        ) : VurderMedlemskapPåNyttResultat()

        data object IngenVurderingFunnet : VurderMedlemskapPåNyttResultat()

        data object PrøvingPågår : VurderMedlemskapPåNyttResultat()
    }

    fun behandleGrunnlagForNyArbeidstakerMedlemskapsvurdering(
        arbeidsforhold: List<Arbeidsforhold>,
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
        medlemskapsprøvingId: MedlemskapsprøvingId,
        saksbehandlerIdent: String,
    ): BehandleGrunnlagResultat =
        medMdc(
            MdcKey.IDENTITETSNUMMER to fødselsnummer,
        ) {
            val prøving =
                medlemskapsprøvingRepository
                    .finnSiste(fødselsnummer, skjæringstidspunkt, Kategori.Arbeidstaker)
                    ?.takeIf { it.id == medlemskapsprøvingId }

            if (prøving == null) {
                loggError(
                    "Mottatt grunnlag for ny medlemskapsvurdering, men fant ikke prøvingen som siste prøving",
                    "medlemskapsprøvingId" to medlemskapsprøvingId.toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
                return@medMdc BehandleGrunnlagResultat.IngenPrøvingFunnet
            }

            if (prøving.erAvsluttet) {
                loggInfo(
                    "Mottatt grunnlag for ny medlemskapsvurdering, men prøving er allerede avsluttet",
                    "medlemskapsprøvingId" to prøving.id.toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                )
                return@medMdc BehandleGrunnlagResultat.AlleredeVurdert
            }

            val vurdering = prøving.motta(Medlemskapsgrunnlag.Arbeidstaker(arbeidsforhold), versjonAvKode)
            medlemskapsprøvingRepository.lagre(prøving)
            lagreOgMeldNyVurdering(vurdering, saksbehandlerIdent)
            loggInfo(
                "Ny medlemskapsprøving fullført",
                "medlemskapsprøvingId" to prøving.id.toString(),
                "medlemskapsvurderingId" to vurdering.id.toString(),
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
            )
            BehandleGrunnlagResultat.NyVurderingForetatt(fødselsnummer, skjæringstidspunkt, vurdering.id)
        }

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

    private val Kategori.arbeidssituasjon: Arbeidssituasjon
        get() =
            when (this) {
                Kategori.Arbeidstaker -> Arbeidssituasjon.Arbeidstaker
                Kategori.SelvstendigNæringsdrivende -> Arbeidssituasjon.SelvstendigNæringsdrivende
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

    fun finnMedlemskapsvurdering(medlemskapsvurderingId: MedlemskapsvurderingId): Medlemskapsvurdering =
        medlemskapsvurderingRepository.finn(medlemskapsvurderingId)
            ?: error("Fant ikke medlemskapsvurdering med id $medlemskapsvurderingId")
}
