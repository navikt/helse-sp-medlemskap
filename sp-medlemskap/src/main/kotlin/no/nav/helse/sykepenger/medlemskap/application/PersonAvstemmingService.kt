package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.tilMedlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.TransaksjonProvider
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.loggWarn

internal class PersonAvstemmingService(
    private val transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
    private val spleisClient: ISpleisClient,
) {
    /**
     * Henter personens vurderinger fra Spleis uten å lagre dem. Gir tom liste hvis personen allerede er
     * migrert, fordi vurderingene da ligger her.
     */
    fun hentMedlemskapsvurderingerSomIkkeErMigrert(fødselsnummer: String): List<Medlemskapsvurdering> {
        if (erMigrert(fødselsnummer)) return emptyList()
        return hentFraSpleis(fødselsnummer)
    }

    fun lagreMedlemskapsvurderinger(fødselsnummer: String): Int {
        if (erMigrert(fødselsnummer)) {
            loggInfo("Person-avstemming er allerede behandlet")
            return 0
        }

        val vurderinger = hentFraSpleis(fødselsnummer)

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
                    antallVurderinger = vurderinger.size,
                    antallHoppetOver = antallHoppetOver,
                )
                antallLagret to antallHoppetOver
            }

        loggInfo(
            "Behandlet person-avstemming",
            "antallMedlemskapsvurderinger" to vurderinger.size.toString(),
            "antallNyeMedlemskapsvurderinger" to antallLagret.toString(),
            "antallHoppetOverMedlemskapsvurderinger" to antallHoppetOver.toString(),
        )
        return antallLagret
    }

    private fun erMigrert(fødselsnummer: String) = transaksjonProvider.transaksjon { it.migreringslogg.finnesFor(fødselsnummer) }

    private fun hentFraSpleis(fødselsnummer: String): List<Medlemskapsvurdering> {
        val spleisVurderinger = spleisClient.hentMedlemskapsvurderinger(fødselsnummer)
        return try {
            spleisVurderinger.map { it.tilMedlemskapsvurdering(fødselsnummer) }
        } catch (ex: IllegalStateException) {
            loggWarn(
                "Ugyldige medlemskapsvurderinger i svaret fra Spleis",
                ex,
                "spleisRespons" to spleisVurderinger.toString(),
            )
            throw ex
        }
    }
}
