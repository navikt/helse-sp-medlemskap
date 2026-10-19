package no.nav.helse.sykepenger.medlemskap.rammeverk.rest

import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.github.smiley4.ktoropenapi.resources.get as documentedGet
import io.github.smiley4.ktoropenapi.resources.post as documentedPost

inline fun <reified RESOURCE : Any, reified RESPONSE, ERROR : ApiErrorCode, TRANSAKSJON> Route.get(
    behandler: GetBehandler<RESOURCE, RESPONSE, ERROR, TRANSAKSJON>,
    restAdapter: RestAdapter<TRANSAKSJON>,
) {
    documentedGet<RESOURCE>({ behandler.openApiUtenRequestBody<RESPONSE, ERROR>(this) }) { resource ->
        restAdapter.håndter(call, resource, behandler) { r, kallKontekst -> behandler.behandle(r, kallKontekst) }
    }
}

inline fun <reified RESOURCE : Any, reified REQUEST : Any, reified RESPONSE, ERROR : ApiErrorCode, TRANSAKSJON> Route.post(
    behandler: PostBehandler<RESOURCE, REQUEST, RESPONSE, ERROR, TRANSAKSJON>,
    restAdapter: RestAdapter<TRANSAKSJON>,
) {
    documentedPost<RESOURCE>({ behandler.openApiMedRequestBody<REQUEST, RESPONSE, ERROR>(this) }) { resource ->
        val request = call.receive<REQUEST>()
        restAdapter.håndter(call, resource, behandler) { r, kallKontekst -> behandler.behandle(r, request, kallKontekst) }
    }
}
