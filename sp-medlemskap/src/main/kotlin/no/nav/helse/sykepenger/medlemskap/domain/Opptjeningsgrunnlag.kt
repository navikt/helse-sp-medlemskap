package no.nav.helse.sykepenger.medlemskap.domain

internal sealed interface Medlemskapsgrunnlag {
    val regel get() = Medlemskapsregel

    /** Behovet dette grunnlaget besvarer, eller null dersom det ikke må innhentes. */
    val besvarer: Grunnlagsbehov?

    data class Arbeidstaker(
        val arbeidsforhold: List<Arbeidsforhold>,
    ) : Medlemskapsgrunnlag {
        override val besvarer = Grunnlagsbehov.Arbeidsforhold
    }

    data object SelvstendigNæringsdrivende : Medlemskapsgrunnlag {
        override val besvarer = null
    }
}

internal enum class Grunnlagsbehov {
    Arbeidsforhold,
}
