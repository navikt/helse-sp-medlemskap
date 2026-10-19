package no.nav.helse.sykepenger.medlemskap.domain

internal enum class Kategori {
    Arbeidstaker,
    SelvstendigNæringsdrivende,
}

internal val Medlemskapsgrunnlag.kategori: Kategori
    get() =
        when (this) {
            is Medlemskapsgrunnlag.Arbeidstaker -> Kategori.Arbeidstaker
            Medlemskapsgrunnlag.SelvstendigNæringsdrivende -> Kategori.SelvstendigNæringsdrivende
        }
