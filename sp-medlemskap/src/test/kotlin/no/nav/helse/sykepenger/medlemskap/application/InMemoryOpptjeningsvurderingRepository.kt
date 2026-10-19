package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import java.time.LocalDate

internal class InMemoryMedlemskapsvurderingRepository : MedlemskapsvurderingRepository {
    private val vurderinger = mutableListOf<Medlemskapsvurdering>()

    internal val alleVurderinger: List<Medlemskapsvurdering> get() = vurderinger.toList()
    internal val antallLagringer get() = vurderinger.size

    override fun lagre(vurdering: Medlemskapsvurdering) {
        check(vurderinger.none { it.id == vurdering.id }) { "Vurdering ${vurdering.id} er allerede lagret. Vurderinger er immutable." }
        vurderinger.add(vurdering)
    }

    override fun gjeldende(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ) = vurderinger.lastOrNull { it.fødselsnummer == fødselsnummer && it.skjæringstidspunkt == skjæringstidspunkt }

    override fun historikk(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ) = vurderinger
        .filter { it.fødselsnummer == fødselsnummer && it.skjæringstidspunkt == skjæringstidspunkt }
        .asReversed()
        .sortedByDescending { it.vurdertTidspunkt }

    override fun finn(medlemskapsvurderingId: MedlemskapsvurderingId) = vurderinger.firstOrNull { it.id == medlemskapsvurderingId }
}
