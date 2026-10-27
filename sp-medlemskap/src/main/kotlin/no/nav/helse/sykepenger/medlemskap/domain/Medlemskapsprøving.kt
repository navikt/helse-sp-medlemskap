package no.nav.helse.sykepenger.medlemskap.domain

import no.nav.helse.Periode
import java.time.Instant
import java.time.LocalDate

/**
 * Prosessen som leder fram til en automatisk [Medlemskapsvurdering].
 *
 * Prøvingen eier livssyklusen — hva vi venter på, og om vi er ferdige — mens selve vurderingen er
 * resultatet den produserer. Medlemskap avgjøres alltid ut fra et oppslag mot medlemskapstjenesten
 * (via behovet `Medlemskap`, som sparkel-medlemskap løser), så en prøving starter alltid med å vente
 * på det svaret. Manuelle vurderinger og vurderinger overført fra Spleis eller Infotrygd har ingen
 * prøving, se [Vurderingskilde].
 */
internal class Medlemskapsprøving private constructor(
    val id: MedlemskapsprøvingId,
    val fødselsnummer: String,
    val skjæringstidspunkt: LocalDate,
    /** Perioden medlemskapstjenesten blir spurt om. */
    val oppslagsperiode: Periode,
    val startet: Instant,
    tilstand: Tilstand,
) {
    var tilstand: Tilstand = tilstand
        private set

    val erAvsluttet get() = tilstand is Tilstand.Fullført

    val uteståendeBehov get() = (tilstand as? Tilstand.VenterPåGrunnlag)?.behov

    sealed interface Tilstand {
        data class VenterPåGrunnlag(
            val behov: Grunnlagsbehov,
        ) : Tilstand

        data class Fullført(
            val medlemskapsvurderingId: MedlemskapsvurderingId,
        ) : Tilstand
    }

    /**
     * Tar imot svaret fra medlemskapstjenesten og produserer medlemskapsvurderingen.
     * Vurderingen og den oppdaterte prøvingen må lagres i samme transaksjon.
     */
    fun motta(
        svar: Medlemskapsstatus,
        versjonAvKode: String = "test",
        aarsaker: List<Årsak>? = null,
    ): Medlemskapsvurdering {
        val venter =
            tilstand as? Tilstand.VenterPåGrunnlag
                ?: error("Prøving $id venter ikke på grunnlag, men er i tilstand $tilstand")
        check(venter.behov == Grunnlagsbehov.Medlemskap) { "Prøving $id venter på ${venter.behov}, ikke medlemskap" }
        val vurdering = Medlemskapsvurdering.automatisk(this, svar, versjonAvKode, aarsaker)
        tilstand = Tilstand.Fullført(vurdering.id)
        return vurdering
    }

    companion object {
        fun start(
            fødselsnummer: String,
            skjæringstidspunkt: LocalDate,
            oppslagsperiode: Periode,
        ) = Medlemskapsprøving(
            id = MedlemskapsprøvingId.ny(),
            fødselsnummer = fødselsnummer,
            skjæringstidspunkt = skjæringstidspunkt,
            oppslagsperiode = oppslagsperiode,
            startet = Instant.now(),
            tilstand = Tilstand.VenterPåGrunnlag(Grunnlagsbehov.Medlemskap),
        )

        fun fraLagring(
            id: MedlemskapsprøvingId,
            fødselsnummer: String,
            skjæringstidspunkt: LocalDate,
            oppslagsperiode: Periode,
            startet: Instant,
            tilstand: Tilstand,
        ) = Medlemskapsprøving(id, fødselsnummer, skjæringstidspunkt, oppslagsperiode, startet, tilstand)
    }
}

internal enum class Grunnlagsbehov {
    Medlemskap,
}
