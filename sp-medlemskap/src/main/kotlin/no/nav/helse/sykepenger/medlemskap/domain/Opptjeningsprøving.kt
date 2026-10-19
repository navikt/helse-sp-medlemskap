package no.nav.helse.sykepenger.medlemskap.domain

import java.time.Instant
import java.time.LocalDate

/**
 * Prosessen som leder fram til en [Medlemskapsvurdering.VurdertISpVilkårsprøving] av medlemskap.
 *
 * Prøvingen eier livssyklusen — hva vi venter på, hvor lenge, og om vi er ferdige — mens selve
 * vurderingen er resultatet den produserer. En prøving i denne appen *er* en automatisk prøving: det
 * finnes ingen prøving for en manuell vurdering eller en vurdering overført fra Spleis eller Infotrygd,
 * se [Vurderingskilde].
 */
internal class Medlemskapsprøving private constructor(
    val id: MedlemskapsprøvingId,
    val fødselsnummer: String,
    val skjæringstidspunkt: LocalDate,
    val kategori: Kategori,
    val startet: Instant,
    tilstand: Tilstand,
) {
    var tilstand: Tilstand = tilstand
        private set

    val erAvsluttet get() = tilstand is Tilstand.Fullført

    val uteståendeBehov get() = (tilstand as? Tilstand.VenterPåGrunnlag)?.behov

    sealed interface Tilstand {
        data object Startet : Tilstand

        data class VenterPåGrunnlag(
            val behov: Grunnlagsbehov,
        ) : Tilstand

        data class Fullført(
            val medlemskapsvurderingId: MedlemskapsvurderingId,
        ) : Tilstand
    }

    /**
     * Tar imot grunnlaget prøvingen venter på og produserer medlemskapsvurderingen.
     * Vurderingen og den oppdaterte prøvingen må lagres i samme transaksjon.
     */
    fun motta(
        grunnlag: Medlemskapsgrunnlag,
        versjonAvKode: String = "test",
    ): Medlemskapsvurdering.VurdertISpVilkårsprøving {
        val venter =
            tilstand as? Tilstand.VenterPåGrunnlag
                ?: error("Prøving $id venter ikke på grunnlag, men er i tilstand $tilstand")
        check(grunnlag.besvarer == venter.behov) {
            "Prøving $id venter på ${venter.behov}, men fikk grunnlag som besvarer ${grunnlag.besvarer}"
        }
        return fullfør(grunnlag, versjonAvKode)
    }

    private fun fullfør(
        grunnlag: Medlemskapsgrunnlag,
        versjonAvKode: String,
    ): Medlemskapsvurdering.VurdertISpVilkårsprøving {
        val vurdering =
            Medlemskapsvurdering.automatisk(
                medlemskapsprøvingId = id,
                fødselsnummer = fødselsnummer,
                skjæringstidspunkt = skjæringstidspunkt,
                grunnlag = grunnlag,
                versjonAvKode = versjonAvKode,
            )
        tilstand = Tilstand.Fullført(vurdering.id)
        return vurdering
    }

    /** En påbegynt prøving. [vurdering] er satt dersom prøvingen kunne fullføres uten å innhente noe. */
    data class Påbegynt(
        val prøving: Medlemskapsprøving,
        val vurdering: Medlemskapsvurdering.VurdertISpVilkårsprøving?,
    )

    companion object {
        /**
         * Starter en prøving av medlemskapskravet.
         *
         * Arbeidstakere må vi hente arbeidsforhold for, mens selvstendig næringsdrivende kan vurderes
         * med en gang.
         */
        fun start(
            fødselsnummer: String,
            skjæringstidspunkt: LocalDate,
            arbeidssituasjon: Arbeidssituasjon,
            versjonAvKode: String = "test",
        ): Påbegynt {
            val umiddelbartGrunnlag =
                when (arbeidssituasjon) {
                    Arbeidssituasjon.Arbeidstaker -> null
                    Arbeidssituasjon.SelvstendigNæringsdrivende -> Medlemskapsgrunnlag.SelvstendigNæringsdrivende
                }
            val prøving =
                Medlemskapsprøving(
                    id = MedlemskapsprøvingId.ny(),
                    fødselsnummer = fødselsnummer,
                    skjæringstidspunkt = skjæringstidspunkt,
                    kategori = arbeidssituasjon.kategori,
                    startet = Instant.now(),
                    tilstand = Tilstand.Startet,
                )
            val vurdering =
                when (umiddelbartGrunnlag) {
                    null -> {
                        prøving.tilstand = Tilstand.VenterPåGrunnlag(Grunnlagsbehov.Arbeidsforhold)
                        null
                    }

                    else -> prøving.fullfør(umiddelbartGrunnlag, versjonAvKode)
                }
            return Påbegynt(prøving, vurdering)
        }

        fun fraLagring(
            id: MedlemskapsprøvingId,
            fødselsnummer: String,
            skjæringstidspunkt: LocalDate,
            kategori: Kategori,
            startet: Instant,
            tilstand: Tilstand,
        ) = Medlemskapsprøving(id, fødselsnummer, skjæringstidspunkt, kategori, startet, tilstand)
    }
}

private val Arbeidssituasjon.kategori: Kategori
    get() =
        when (this) {
            Arbeidssituasjon.Arbeidstaker -> Kategori.Arbeidstaker
            Arbeidssituasjon.SelvstendigNæringsdrivende -> Kategori.SelvstendigNæringsdrivende
        }
