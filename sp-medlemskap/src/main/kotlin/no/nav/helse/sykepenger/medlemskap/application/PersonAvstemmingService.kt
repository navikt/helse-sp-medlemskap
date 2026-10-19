package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.tilMedlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.TransaksjonProvider
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.loggWarn

internal class PersonAvstemmingService(
    private val transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
    private val spleisClient: ISpleisClient,
) {
    fun lagreMedlemskapsvurderinger(fødselsnummer: String): Int {
        if (transaksjonProvider.transaksjon { it.migreringslogg.finnesFor(fødselsnummer) }) {
            loggInfo("Person-avstemming er allerede behandlet")
            return 0
        }

        val spleisVurderinger = spleisClient.hentMedlemskapsvurderinger(fødselsnummer)
        val vurderinger =
            try {
                spleisVurderinger.map { it.tilMedlemskapsvurdering(fødselsnummer) }
            } catch (ex: IllegalStateException) {
                loggWarn(
                    "Ugyldige medlemskapsvurderinger i svaret fra Spleis",
                    ex,
                    "spleisRespons" to spleisVurderinger.toString(),
                )
                throw ex
            }

        val (antallLagret, antallHoppetOver) =
            transaksjonProvider.transaksjon { kontekst ->
                var antallLagret = 0
                var antallHoppetOver = 0
                vurderinger.forEach { vurdering ->
                    val eksisterende = kontekst.medlemskapsvurderinger.finn(vurdering.id)
                    if (eksisterende == null) {
                        kontekst.medlemskapsvurderinger.lagre(vurdering)
                        antallLagret++
                    } else {
                        check(eksisterende.fødselsnummer == fødselsnummer) {
                            "Medlemskapsvurdering ${vurdering.id} er allerede lagret for en annen person"
                        }
                        antallHoppetOver++
                    }
                }
                kontekst.migreringslogg.lagre(
                    fødselsnummer = fødselsnummer,
                    antallVurderinger = spleisVurderinger.size,
                    antallHoppetOver = antallHoppetOver,
                )
                antallLagret to antallHoppetOver
            }

        loggInfo(
            "Behandlet person-avstemming",
            "antallMedlemskapsvurderinger" to spleisVurderinger.size.toString(),
            "antallNyeMedlemskapsvurderinger" to antallLagret.toString(),
            "antallHoppetOverMedlemskapsvurderinger" to antallHoppetOver.toString(),
        )
        return antallLagret
    }
}
