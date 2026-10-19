package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import no.nav.helse.sykepenger.medlemskap.application.MedlemskapService
import no.nav.helse.sykepenger.medlemskap.application.MedlemskapService.VurderMedlemskapPåNyttResultat
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Tilgang
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.KallKontekst
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.PostBehandler
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.RestResponse
import no.nav.sykepenger.libs.logging.loggInfo
import java.time.LocalDate

internal class PostNyAutomatiskVilkårsvurderingBehandler(
    private val publiser: (key: String, melding: String) -> Unit,
    private val versjonAvKode: String = "test",
) : PostBehandler<
        ApiNyAutomatiskVilkårsvurderingResource,
        ApiNyAutomatiskVilkårsvurderingRequest,
        ApiNyAutomatiskVilkårsvurderingResponse,
        ApiNyAutomatiskVilkårsvurderingFeil,
        Transaksjonskontekst,
    > {
    override val påkrevdTilgang = Tilgang.Skriv
    override val tag = "vilkarsvurderinger"

    override fun behandle(
        resource: ApiNyAutomatiskVilkårsvurderingResource,
        request: ApiNyAutomatiskVilkårsvurderingRequest,
        kallKontekst: KallKontekst<Transaksjonskontekst>,
    ): RestResponse<ApiNyAutomatiskVilkårsvurderingResponse, ApiNyAutomatiskVilkårsvurderingFeil> {
        val personPseudoId =
            PersonPseudoId.fraString(resource.personId)
                ?: return RestResponse.feil(ApiNyAutomatiskVilkårsvurderingFeil.PersonIkkeFunnet)

        return kallKontekst.medPerson(
            personPseudoId = personPseudoId,
            personIkkeFunnet = { ApiNyAutomatiskVilkårsvurderingFeil.PersonIkkeFunnet },
            manglerTilgang = { ApiNyAutomatiskVilkårsvurderingFeil.ManglerTilgang },
        ) { identitetsnummer ->
            val resultat =
                MedlemskapService(kallKontekst.transaksjon, versjonAvKode).vurderMedlemskapPåNytt(
                    fødselsnummer = identitetsnummer.value,
                    skjæringstidspunkt = request.skjæringstidspunkt,
                    saksbehandlerIdent = kallKontekst.saksbehandler.navIdent.value,
                )

            when (resultat) {
                is VurderMedlemskapPåNyttResultat.NyVurderingForetatt ->
                    RestResponse.ok(
                        ApiNyAutomatiskVilkårsvurderingResponse(
                            status = ApiNyAutomatiskVilkårsvurderingStatus.VURDERT,
                            medlemskapsvurderingId = resultat.medlemskapsvurderingId.value,
                        ),
                    )

                is VurderMedlemskapPåNyttResultat.VenterPåGrunnlag -> {
                    beOmArbeidsforhold(
                        identitetsnummer = identitetsnummer,
                        skjæringstidspunkt = request.skjæringstidspunkt,
                        medlemskapsprøvingId = resultat.medlemskapsprøvingId,
                        saksbehandlerIdent = kallKontekst.saksbehandler.navIdent.value,
                    )
                    RestResponse.ok(ApiNyAutomatiskVilkårsvurderingResponse(status = ApiNyAutomatiskVilkårsvurderingStatus.VENTER_PÅ_GRUNNLAG))
                }

                VurderMedlemskapPåNyttResultat.IngenVurderingFunnet ->
                    RestResponse.feil(ApiNyAutomatiskVilkårsvurderingFeil.MedlemskapsvurderingIkkeFunnet)

                VurderMedlemskapPåNyttResultat.PrøvingPågår ->
                    RestResponse.feil(ApiNyAutomatiskVilkårsvurderingFeil.MedlemskapsprøvingPågår)
            }
        }
    }

    private fun beOmArbeidsforhold(
        identitetsnummer: Identitetsnummer,
        skjæringstidspunkt: LocalDate,
        medlemskapsprøvingId: MedlemskapsprøvingId,
        saksbehandlerIdent: String,
    ) {
        val behov =
            JsonMessage.newNeed(
                behov = listOf("ArbeidsforholdV2"),
                map =
                    mapOf(
                        "fødselsnummer" to identitetsnummer.value,
                        "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                        "medlemskapsprøvingId" to medlemskapsprøvingId.toString(),
                        "saksbehandlerIdent" to saksbehandlerIdent,
                    ),
            )
        loggInfo(
            "Publiserer behov for arbeidsforhold til ny medlemskapsvurdering",
            "medlemskapsprøvingId" to medlemskapsprøvingId.toString(),
            "skjæringstidspunkt" to skjæringstidspunkt.toString(),
        )
        publiser(identitetsnummer.value, behov.toJson())
    }
}
