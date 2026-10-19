package no.nav.helse.sykepenger.medlemskap.infra.rest

import io.ktor.resources.Resource

@Resource("/api/personer/{personId}/saksbehandlerhandlinger/{handlingId}/visning")
internal class ApiSaksbehandlerhandlingVisningResource(
    val personId: String,
    val handlingId: String,
)
