package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import no.nav.helse.sykepenger.medlemskap.application.HandlingType
import no.nav.helse.sykepenger.medlemskap.application.OutboxKonvolutt
import no.nav.helse.sykepenger.medlemskap.application.OutboxMelding
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Tilgang
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.KallKontekst
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.PostBehandler
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.RestResponse
import java.time.Instant

/**
 * Er [overstyringErSkruddPå] av, avvises kallet. Overstyring skal ikke skrus på før sp-medlemskap gjør
 * vurderingen selv, siden en manuell vurdering gjør sp-medlemskap til eier av vurderingen.
 *
 * Om vurderingen krever totrinnsvurdering, avgjøres ved å sammenligne med gjeldende vurdering. Låsen hindrer
 * at to samtidige manuelle vurderinger av samme person sammenlignes med den samme gjeldende vurderingen. Den
 * hindrer ikke at en automatisk vurdering eller migrering lagres i mellomtiden.
 *
 * Forbedringspotensial: Saksbehandler kan ha sett en eldre vurdering enn den som er gjeldende når kallet
 * kommer, for eksempel hvis det har kommet en ny vurdering mens saken var åpen i Speil. La Speil sende med
 * id-en til vurderingen saksbehandler så på, og svar med 409 Conflict hvis den ikke lenger er gjeldende.
 */
internal class PostManuellVilkårsvurderingBehandler(
    private val overstyringErSkruddPå: Boolean = true,
) : PostBehandler<
        ApiManuellVilkårsvurderingResource,
        ApiManuellVilkårsvurderingRequest,
        ApiManuellVilkårsvurderingResponse,
        ApiManuellVilkårsvurderingFeil,
        Transaksjonskontekst,
    > {
    override val påkrevdTilgang = Tilgang.Skriv
    override val tag = "vilkarsvurderinger"

    override fun behandle(
        resource: ApiManuellVilkårsvurderingResource,
        request: ApiManuellVilkårsvurderingRequest,
        kallKontekst: KallKontekst<Transaksjonskontekst>,
    ): RestResponse<ApiManuellVilkårsvurderingResponse, ApiManuellVilkårsvurderingFeil> {
        if (!overstyringErSkruddPå) return RestResponse.feil(ApiManuellVilkårsvurderingFeil.OverstyringErSkruddAv)

        val personPseudoId =
            PersonPseudoId.fraString(resource.personId)
                ?: return RestResponse.feil(ApiManuellVilkårsvurderingFeil.PersonIkkeFunnet)

        return kallKontekst.medPerson(
            personPseudoId = personPseudoId,
            personIkkeFunnet = { ApiManuellVilkårsvurderingFeil.PersonIkkeFunnet },
            manglerTilgang = { ApiManuellVilkårsvurderingFeil.ManglerTilgang },
        ) { identitetsnummer ->
            kallKontekst.transaksjon.medlemskapsvurderinger.låsFor(identitetsnummer.value)
            val gjeldendeVurdering =
                kallKontekst.transaksjon.medlemskapsvurderinger
                    .gjeldende(identitetsnummer.value, request.skjæringstidspunkt)

            val kravvurdering =
                Medlemskapsvurdering.avSaksbehandler(
                    fødselsnummer = identitetsnummer.value,
                    skjæringstidspunkt = request.skjæringstidspunkt,
                    status = request.status.fraApi(),
                    saksbehandlerIdent = kallKontekst.saksbehandler.navIdent.value,
                    fritekstbegrunnelse = request.fritekstbegrunnelse,
                    journalpostId = request.journalpostId,
                )

            val kreverTotrinnsvurdering = (gjeldendeVurdering != null) && (gjeldendeVurdering.status.erOk != kravvurdering.status.erOk)

            kallKontekst.transaksjon.medlemskapsvurderinger.lagre(kravvurdering)

            kallKontekst.transaksjon.outbox.leggTil(
                OutboxKonvolutt.ny(
                    melding =
                        OutboxMelding.Saksbehandlerhandling(
                            skjæringstidspunkt = request.skjæringstidspunkt,
                            saksbehandlerIdent = kallKontekst.saksbehandler.navIdent.value,
                            handlingId = kravvurdering.id.value,
                            handlingType = HandlingType.MANUELT_VURDERT_MEDLEMSKAP,
                            kreverTotrinnsvurdering = kreverTotrinnsvurdering,
                            tidspunkt = Instant.now(),
                        ),
                    identitetsnummer = identitetsnummer,
                ),
            )
            kallKontekst.transaksjon.outbox.leggTil(
                OutboxKonvolutt.ny(
                    melding =
                        OutboxMelding.MedlemskapsvurderingEndret(
                            skjæringstidspunkt = request.skjæringstidspunkt,
                            medlemskapsvurderingId = kravvurdering.id.value,
                            manuellVurdering = true,
                        ),
                    identitetsnummer = identitetsnummer,
                ),
            )

            RestResponse.ok(ApiManuellVilkårsvurderingResponse(medlemskapsvurderingId = kravvurdering.id.value))
        }
    }
}

private fun ApiManuellMedlemskapsstatus.fraApi(): Medlemskapsstatus =
    when (this) {
        ApiManuellMedlemskapsstatus.JA -> Medlemskapsstatus.Ja
        ApiManuellMedlemskapsstatus.NEI -> Medlemskapsstatus.Nei
    }
