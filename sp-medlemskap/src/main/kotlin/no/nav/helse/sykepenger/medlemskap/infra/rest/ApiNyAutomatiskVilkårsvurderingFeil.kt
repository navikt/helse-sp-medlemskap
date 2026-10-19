package no.nav.helse.sykepenger.medlemskap.infra.rest

import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.ApiErrorCode

internal enum class ApiNyAutomatiskVilkårsvurderingFeil(
    override val httpStatus: Int,
    override val tittel: String,
) : ApiErrorCode {
    PersonIkkeFunnet(404, "Person ikke funnet"),
    ManglerTilgang(403, "Mangler tilgang"),
    MedlemskapsvurderingIkkeFunnet(404, "Fant ingen medlemskapsvurdering å vurdere på nytt"),
    MedlemskapsprøvingPågår(409, "Medlemskapsprøving pågår allerede"),
}
