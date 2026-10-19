package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import no.nav.helse.sykepenger.medlemskap.application.HandlingType
import no.nav.helse.sykepenger.medlemskap.application.OutboxKonvolutt
import no.nav.helse.sykepenger.medlemskap.application.OutboxMelding
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.Utfall
import no.nav.helse.sykepenger.medlemskap.domain.Vilkårskode
import no.nav.helse.sykepenger.medlemskap.domain.Vilkårsvurdering
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Tilgang
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.KallKontekst
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.PostBehandler
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.RestResponse
import java.time.Instant

internal class PostManuellVilkårsvurderingBehandler :
    PostBehandler<
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
        val personPseudoId =
            PersonPseudoId.fraString(resource.personId)
                ?: return RestResponse.feil(ApiManuellVilkårsvurderingFeil.PersonIkkeFunnet)

        return kallKontekst.medPerson(
            personPseudoId = personPseudoId,
            personIkkeFunnet = { ApiManuellVilkårsvurderingFeil.PersonIkkeFunnet },
            manglerTilgang = { ApiManuellVilkårsvurderingFeil.ManglerTilgang },
        ) { identitetsnummer ->
            val vilkårskode = request.vilkårskode.fraApi()

            val vilkårsvurdering =
                Vilkårsvurdering.avSaksbehandler(
                    vilkårskode = vilkårskode,
                    utfall = request.utfall.fraApi(),
                    saksbehandlerIdent = kallKontekst.saksbehandler.navIdent.value,
                    fritekstbegrunnelse = request.fritekstbegrunnelse,
                    journalpostId = request.journalpostId,
                )

            val kravvurdering =
                Medlemskapsvurdering.avSaksbehandler(
                    fødselsnummer = identitetsnummer.value,
                    skjæringstidspunkt = request.skjæringstidspunkt,
                    vilkårsvurdering = vilkårsvurdering,
                    forrigeVurdering =
                        kallKontekst.transaksjon.medlemskapsvurderinger.gjeldende(
                            fødselsnummer = identitetsnummer.value,
                            skjæringstidspunkt = request.skjæringstidspunkt,
                        ),
                )

            kallKontekst.transaksjon.medlemskapsvurderinger.lagre(kravvurdering)

            kallKontekst.transaksjon.outbox.leggTil(
                OutboxKonvolutt.ny(
                    melding =
                        OutboxMelding.Saksbehandlerhandling(
                            skjæringstidspunkt = request.skjæringstidspunkt,
                            saksbehandlerIdent = kallKontekst.saksbehandler.navIdent.value,
                            handlingId = kravvurdering.id.value,
                            handlingType = HandlingType.MANUELT_VURDERT_MEDLEMSKAP,
                            kreverTotrinnsvurdering = true,
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

private fun ApiVilkårskode.fraApi(): Vilkårskode =
    when (this) {
        ApiVilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER -> Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER
    }

private fun ApiUtfall.fraApi(): Utfall =
    when (this) {
        ApiUtfall.OPPFYLT -> Utfall.Oppfylt
        ApiUtfall.IKKE_OPPFYLT -> Utfall.IkkeOppfylt
    }
