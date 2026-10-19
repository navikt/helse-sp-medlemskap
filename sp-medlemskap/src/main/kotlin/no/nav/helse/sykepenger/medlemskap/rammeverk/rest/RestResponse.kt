package no.nav.helse.sykepenger.medlemskap.rammeverk.rest

/** Standard rammeverksfeil som `RestAdapter` selv kan produsere (autentisering, autorisasjon, person). */
enum class RammeverkFeilkode(
    override val httpStatus: Int,
    override val tittel: String,
) : ApiErrorCode {
    Uautentisert(401, "Uautentisert"),
    ManglerTilgang(403, "Mangler tilgang"),
    PersonIkkeFunnet(404, "Person ikke funnet"),
    ValideringsFeil(400, "Ugyldig forespørsel"),
    InternFeil(500, "Intern serverfeil"),
}

/**
 * Resultatet av en `RestBehandler`. `RESPONSE` er suksess-responsen, `ERROR` er appens egen
 * feilkode-enum (implementerer [ApiErrorCode]). `RestAdapter` mapper dette videre til HTTP-status og
 * RFC 7807 problem+json.
 */
sealed interface RestResponse<out RESPONSE, out ERROR : ApiErrorCode> {
    data class Ok<RESPONSE>(
        val body: RESPONSE,
    ) : RestResponse<RESPONSE, Nothing>

    data class Feil<ERROR : ApiErrorCode>(
        val feil: ERROR,
        val detalj: String? = null,
    ) : RestResponse<Nothing, ERROR>

    companion object {
        fun <RESPONSE> ok(body: RESPONSE): RestResponse<RESPONSE, Nothing> = Ok(body)

        fun <ERROR : ApiErrorCode> feil(
            feil: ERROR,
            detalj: String? = null,
        ): RestResponse<Nothing, ERROR> = Feil(feil, detalj)
    }
}
