package no.nav.helse.sykepenger.medlemskap.infra.rest

import io.ktor.resources.Resource

@Resource("/api/personer/{personId}/vilkarsvurderinger/manuell")
internal class ApiManuellVilkårsvurderingResource(
    val personId: String,
)
