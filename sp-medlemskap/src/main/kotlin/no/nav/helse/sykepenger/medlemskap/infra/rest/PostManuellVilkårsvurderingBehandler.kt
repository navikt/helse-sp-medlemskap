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
import no.nav.sykepenger.libs.logging.loggInfo
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
            val gjeldende = kallKontekst.transaksjon.medlemskapsvurderinger.gjeldende(identitetsnummer.value, request.skjæringstidspunkt)
            if (gjeldende == null || gjeldende.id.value != request.gjeldendeMedlemskapsvurderingId) {
                loggInfo(
                    "Avviser manuell medlemskapsvurdering fordi saksbehandler så på en vurdering som ikke er gjeldende",
                    "forespurtMedlemskapsvurderingId" to request.gjeldendeMedlemskapsvurderingId.toString(),
                    "gjeldendeMedlemskapsvurderingId" to gjeldende?.id?.value?.toString(),
                )
                return@medPerson RestResponse.feil(ApiManuellVilkårsvurderingFeil.UtdatertVurdering)
            }

            val kravvurdering =
                Medlemskapsvurdering.avSaksbehandler(
                    fødselsnummer = identitetsnummer.value,
                    skjæringstidspunkt = request.skjæringstidspunkt,
                    status = request.status.fraApi(),
                    saksbehandlerIdent = kallKontekst.saksbehandler.navIdent.value,
                    fritekstbegrunnelse = request.fritekstbegrunnelse,
                    journalpostId = request.journalpostId,
                )

            kallKontekst.transaksjon.medlemskapsvurderinger.lagre(kravvurdering)
            // To vurderinger som erstatter samme id, avslører en samtidig lagring som sjekken over ikke fanger
            loggInfo(
                "Manuell medlemskapsvurdering erstatter gjeldende vurdering",
                "erstattetMedlemskapsvurderingId" to gjeldende.id.value.toString(),
                "nyMedlemskapsvurderingId" to kravvurdering.id.value.toString(),
            )

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

private fun ApiManuellMedlemskapsstatus.fraApi(): Medlemskapsstatus =
    when (this) {
        ApiManuellMedlemskapsstatus.JA -> Medlemskapsstatus.Ja
        ApiManuellMedlemskapsstatus.NEI -> Medlemskapsstatus.Nei
    }
