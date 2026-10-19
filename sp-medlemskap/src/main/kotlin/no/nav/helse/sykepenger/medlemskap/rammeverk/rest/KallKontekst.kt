package no.nav.helse.sykepenger.medlemskap.rammeverk.rest

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoIdProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.AccessToken
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Saksbehandler
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Tilgang
import no.nav.sykepenger.libs.logging.loggDebug

class KallKontekst<TRANSAKSJON>(
    val saksbehandler: Saksbehandler,
    val tilganger: Set<Tilgang>,
    val transaksjon: TRANSAKSJON,
    val accessToken: AccessToken,
    private val personPseudoIdProvider: PersonPseudoIdProvider,
    private val populasjonstilgangskontrollProvider: PopulasjonstilgangskontrollProvider,
) {
    fun <RESPONSE, ERROR : ApiErrorCode> medPerson(
        personPseudoId: PersonPseudoId,
        personIkkeFunnet: () -> ERROR,
        manglerTilgang: () -> ERROR,
        block: (Identitetsnummer) -> RestResponse<RESPONSE, ERROR>,
    ): RestResponse<RESPONSE, ERROR> {
        val identitetsnummer =
            personPseudoIdProvider.finnIdentitetsnummer(personPseudoId)
                ?: return RestResponse.feil(personIkkeFunnet())

        val tilgangsresultat =
            populasjonstilgangskontrollProvider.kontrollerKjerneTilgang(accessToken.value, identitetsnummer.value)

        return when (tilgangsresultat) {
            is TilgangskontrollResultat.Ok -> block(identitetsnummer)
            is TilgangskontrollResultat.ManglerTilgang -> {
                loggDebug(
                    "403: populasjonstilgangskontrollen ga avslag",
                    "navIdent" to saksbehandler.navIdent.value,
                    "tilgangSomMangler" to tilgangsresultat.tilgangSomMangler.toString(),
                )
                RestResponse.feil(manglerTilgang())
            }
            is TilgangskontrollResultat.IdentIkkeFunnet -> RestResponse.feil(personIkkeFunnet())
            is TilgangskontrollResultat.UventetFeil -> {
                // Samme 403 som et ekte avslag, men helt annen årsak: her klarte ikke
                // tilgangsmaskinen å ta en beslutning. Forklaringen kastes ellers bort.
                loggDebug(
                    "403: tilgangskontrollen feilet uventet (ikke et avslag)",
                    "navIdent" to saksbehandler.navIdent.value,
                    "forklaring" to tilgangsresultat.menneskeligLesbarForklaring,
                )
                RestResponse.feil(manglerTilgang())
            }
        }
    }
}
