package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import no.nav.helse.sykepenger.medlemskap.application.PersonAvstemmingService
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisClientException
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Tilgang
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.GetBehandler
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.KallKontekst
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.RestResponse
import no.nav.sykepenger.libs.logging.loggWarn

/**
 * Er [migrerVedOppslag] av, leser endepunktet vurderinger fra Spleis uten å lagre dem. Da kan Speil
 * vise vurderingene før sp-medlemskap gjør vurderingen selv, uten at migreringen av historikken starter.
 */
internal class GetVilkårsvurderingerForPersonBehandler(
    private val avstemmingService: PersonAvstemmingService,
    private val migrerVedOppslag: Boolean = true,
) : GetBehandler<ApiVilkårsvurderingerForPersonResource, ApiVilkårsvurderingerForPersonResponse, ApiVilkårsvurderingerForPersonFeil, Transaksjonskontekst> {
    override val påkrevdTilgang = Tilgang.Les
    override val tag = "vilkarsvurderinger"

    override fun behandle(
        resource: ApiVilkårsvurderingerForPersonResource,
        kallKontekst: KallKontekst<Transaksjonskontekst>,
    ): RestResponse<ApiVilkårsvurderingerForPersonResponse, ApiVilkårsvurderingerForPersonFeil> {
        val personPseudoId =
            PersonPseudoId.fraString(resource.personId)
                ?: return RestResponse.feil(ApiVilkårsvurderingerForPersonFeil.PersonIkkeFunnet)

        return kallKontekst.medPerson(
            personPseudoId = personPseudoId,
            personIkkeFunnet = { ApiVilkårsvurderingerForPersonFeil.PersonIkkeFunnet },
            manglerTilgang = { ApiVilkårsvurderingerForPersonFeil.ManglerTilgang },
        ) { identitetsnummer ->
            val medlemskapsvurderingId = MedlemskapsvurderingId(resource.medlemskapsvurderingId)
            if (!migrerVedOppslag) {
                val vurdering =
                    kallKontekst.transaksjon.medlemskapsvurderinger.finn(medlemskapsvurderingId)
                        ?: try {
                            avstemmingService
                                .hentMedlemskapsvurderingerSomIkkeErMigrert(identitetsnummer.value)
                                .find { it.id == medlemskapsvurderingId }
                        } catch (ex: SpleisClientException) {
                            loggWarn("Feil ved oppslag av medlemskapsvurderinger i Spleis", ex)
                            return@medPerson RestResponse.feil(ApiVilkårsvurderingerForPersonFeil.SpleisUtilgjengelig)
                        }
                if (vurdering == null || vurdering.fødselsnummer != identitetsnummer.value) {
                    return@medPerson RestResponse.feil(ApiVilkårsvurderingerForPersonFeil.VurderingIkkeFunnet)
                }
                return@medPerson RestResponse.ok(Vurderingsrespons.fra(vurdering))
            }

            val spleisFeilet =
                try {
                    avstemmingService.lagreMedlemskapsvurderinger(fødselsnummer = identitetsnummer.value)
                    false
                } catch (ex: SpleisClientException) {
                    loggWarn("Feil ved avstemming av medlemskapsvurderinger mot Spleis", ex)
                    true
                }

            val vurdering =
                kallKontekst.transaksjon.medlemskapsvurderinger
                    .finn(medlemskapsvurderingId)

            if (vurdering == null && spleisFeilet) {
                return@medPerson RestResponse.feil(ApiVilkårsvurderingerForPersonFeil.SpleisUtilgjengelig)
            }
            if (vurdering == null || vurdering.fødselsnummer != identitetsnummer.value) {
                return@medPerson RestResponse.feil(ApiVilkårsvurderingerForPersonFeil.VurderingIkkeFunnet)
            }

            RestResponse.ok(Vurderingsrespons.fra(vurdering))
        }
    }
}
