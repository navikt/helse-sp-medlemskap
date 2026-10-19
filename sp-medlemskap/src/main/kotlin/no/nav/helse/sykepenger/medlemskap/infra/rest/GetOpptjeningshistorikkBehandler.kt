package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import no.nav.helse.sykepenger.medlemskap.application.PersonAvstemmingService
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisClientException
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Tilgang
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.GetBehandler
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.KallKontekst
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.RestResponse
import no.nav.sykepenger.libs.logging.loggWarn

internal class GetMedlemskapshistorikkBehandler(
    private val avstemmingService: PersonAvstemmingService,
) : GetBehandler<ApiMedlemskapshistorikkResource, ApiMedlemskapshistorikkResponse, ApiMedlemskapshistorikkFeil, Transaksjonskontekst> {
    override val påkrevdTilgang = Tilgang.Les
    override val tag = "vilkarsvurderinger"

    override fun behandle(
        resource: ApiMedlemskapshistorikkResource,
        kallKontekst: KallKontekst<Transaksjonskontekst>,
    ): RestResponse<ApiMedlemskapshistorikkResponse, ApiMedlemskapshistorikkFeil> {
        val personPseudoId =
            PersonPseudoId.fraString(resource.personId)
                ?: return RestResponse.feil(ApiMedlemskapshistorikkFeil.PersonIkkeFunnet)

        return kallKontekst.medPerson(
            personPseudoId = personPseudoId,
            personIkkeFunnet = { ApiMedlemskapshistorikkFeil.PersonIkkeFunnet },
            manglerTilgang = { ApiMedlemskapshistorikkFeil.ManglerTilgang },
        ) { identitetsnummer ->
            // Avstemming kaller bare spleis for personer som ikke er migrert ennå. Feiler den, mangler
            // vi altså spleis' del av historikken, og et ufullstendig svar ville sett ut som en fullstendig historikk.
            try {
                avstemmingService.lagreMedlemskapsvurderinger(fødselsnummer = identitetsnummer.value)
            } catch (ex: SpleisClientException) {
                loggWarn("Feil ved avstemming av medlemskapsvurderinger mot Spleis", ex)
                return@medPerson RestResponse.feil(ApiMedlemskapshistorikkFeil.SpleisUtilgjengelig)
            }

            val historikk =
                kallKontekst.transaksjon.medlemskapsvurderinger
                    .historikk(identitetsnummer.value, resource.skjæringstidspunkt)

            RestResponse.ok(Vurderingsrespons.historikk(resource.skjæringstidspunkt, historikk))
        }
    }
}
