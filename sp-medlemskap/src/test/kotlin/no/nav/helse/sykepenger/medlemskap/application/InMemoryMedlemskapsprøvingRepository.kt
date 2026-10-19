package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsprøving
import java.time.LocalDate

internal class InMemoryMedlemskapsprøvingRepository : MedlemskapsprøvingRepository {
    private val prøvinger = mutableListOf<Medlemskapsprøving>()

    internal val allePrøvinger: List<Medlemskapsprøving> get() = prøvinger.toList()

    override fun lagre(prøving: Medlemskapsprøving) {
        val eksisterende = prøvinger.indexOfFirst { it.id == prøving.id }
        if (eksisterende != -1) {
            prøvinger[eksisterende] = prøving
            return
        }
        check(prøvinger.none { it.gjelderSammeSom(prøving) && !it.erAvsluttet }) {
            "Det pågår allerede en medlemskapsprøving for fødselsnummer ${prøving.fødselsnummer} med skjæringstidspunkt ${prøving.skjæringstidspunkt}"
        }
        prøvinger.add(prøving)
    }

    override fun finnSiste(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ) = prøvinger.lastOrNull { it.fødselsnummer == fødselsnummer && it.skjæringstidspunkt == skjæringstidspunkt }

    private fun Medlemskapsprøving.gjelderSammeSom(annen: Medlemskapsprøving) =
        fødselsnummer == annen.fødselsnummer &&
            skjæringstidspunkt == annen.skjæringstidspunkt
}
