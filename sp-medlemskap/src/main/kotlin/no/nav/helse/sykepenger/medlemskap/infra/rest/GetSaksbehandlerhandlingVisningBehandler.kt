package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Vurderingskilde
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Tilgang
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.GetBehandler
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.KallKontekst
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.RestResponse
import java.util.UUID

internal class GetSaksbehandlerhandlingVisningBehandler :
    GetBehandler<
        ApiSaksbehandlerhandlingVisningResource,
        ApiSaksbehandlerhandlingVisningResponse,
        ApiSaksbehandlerhandlingVisningFeil,
        Transaksjonskontekst,
    > {
    override val påkrevdTilgang = Tilgang.Les
    override val tag = "saksbehandlerhandlinger"

    override fun behandle(
        resource: ApiSaksbehandlerhandlingVisningResource,
        kallKontekst: KallKontekst<Transaksjonskontekst>,
    ): RestResponse<ApiSaksbehandlerhandlingVisningResponse, ApiSaksbehandlerhandlingVisningFeil> {
        val personPseudoId =
            PersonPseudoId.fraString(resource.personId)
                ?: return RestResponse.feil(ApiSaksbehandlerhandlingVisningFeil.PersonIkkeFunnet)

        return kallKontekst.medPerson(
            personPseudoId = personPseudoId,
            personIkkeFunnet = { ApiSaksbehandlerhandlingVisningFeil.PersonIkkeFunnet },
            manglerTilgang = { ApiSaksbehandlerhandlingVisningFeil.ManglerTilgang },
        ) { identitetsnummer ->
            val ikkeFunnet = RestResponse.feil(ApiSaksbehandlerhandlingVisningFeil.VisningIkkeFunnet)

            val medlemskapsvurderingId =
                runCatching { UUID.fromString(resource.handlingId) }.getOrNull()
                    ?: return@medPerson ikkeFunnet

            val vurdering =
                kallKontekst.transaksjon.medlemskapsvurderinger
                    .finn(MedlemskapsvurderingId(medlemskapsvurderingId))
                    ?.takeIf { it.fødselsnummer == identitetsnummer.value }
                    ?: return@medPerson ikkeFunnet

            val kilde = vurdering.kilde as? Vurderingskilde.Saksbehandler ?: return@medPerson ikkeFunnet

            RestResponse.ok(ApiSaksbehandlerhandlingVisningResponse(felter = tilVisningsfelter(vurdering.status, kilde)))
        }
    }
}

private fun tilVisningsfelter(
    status: Medlemskapsstatus,
    kilde: Vurderingskilde.Saksbehandler,
): List<ApiVisningsfelt> =
    buildList {
        add(ApiVisningsfelt.Tekst(tittel = "Medlemskap", tekst = status.visningsnavn()))
        add(ApiVisningsfelt.Tekst(tittel = "Begrunnelse", tekst = kilde.fritekstbegrunnelse))
        if (kilde.journalpostId.isNotEmpty()) {
            add(ApiVisningsfelt.Liste(tittel = "Journalposter", elementer = kilde.journalpostId))
        }
    }

private fun Medlemskapsstatus.visningsnavn(): String =
    when (this) {
        Medlemskapsstatus.Ja -> "Medlem i folketrygden"
        Medlemskapsstatus.Nei -> "Ikke medlem i folketrygden"
        Medlemskapsstatus.Uavklart -> "Uavklart"
        Medlemskapsstatus.UavklartMedBrukerspørsmål -> "Uavklart med brukerspørsmål"
    }
