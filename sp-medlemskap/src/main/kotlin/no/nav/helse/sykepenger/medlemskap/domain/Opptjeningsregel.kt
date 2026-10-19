package no.nav.helse.sykepenger.medlemskap.domain

import no.nav.helse.Periode.Companion.grupperSammenhengendePerioderMedHensynTilHelg
import no.nav.helse.forrigeDag
import no.nav.helse.til
import java.time.LocalDate

internal object Medlemskapsregel {
    private const val ANTALL_MEDLEMSKAPSDAGER_SOM_KREVES = 28

    fun vurder(
        skjæringstidspunkt: LocalDate,
        grunnlag: Medlemskapsgrunnlag,
    ): MedlemskapsregelResultat =
        when (grunnlag) {
            is Medlemskapsgrunnlag.Arbeidstaker -> vurderArbeidstaker(skjæringstidspunkt, grunnlag.arbeidsforhold)
            Medlemskapsgrunnlag.SelvstendigNæringsdrivende -> vurderSelvstendigNæringsdrivende()
        }

    private fun vurderArbeidstaker(
        skjæringstidspunkt: LocalDate,
        arbeidsforhold: List<Arbeidsforhold>,
    ): MedlemskapsregelResultat {
        val dagenFør = skjæringstidspunkt.forrigeDag
        val medlemskapsperiode =
            arbeidsforhold
                .filter { it.type != Arbeidsforhold.Arbeidsforholdtype.FRILANSER }
                .map { it.ansettelseperiode }
                .filterNot { it.start > dagenFør }
                .map { it.subset(it.start til dagenFør) }
                .grupperSammenhengendePerioderMedHensynTilHelg()
                .find { it.erRettFør(skjæringstidspunkt) }
                // Et arbeidsforhold som slutter fredag/lørdag regnes som løpende over helgen fram til skjæringstidspunktet
                ?.let { it.start til dagenFør }
        val medlemskapsdager = medlemskapsperiode?.count() ?: 0

        return MedlemskapsregelResultat(
            listOf(
                Vilkårsutfall(
                    vilkårskode = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                    utfall = if (medlemskapsdager >= ANTALL_MEDLEMSKAPSDAGER_SOM_KREVES) Utfall.Oppfylt else Utfall.IkkeOppfylt,
                    utledetFakta = UtledetFakta.Medlemskapstid(medlemskapsperiode, medlemskapsdager),
                    lovreferanse = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER.lovreferanse,
                ),
            ),
        )
    }

    private fun vurderSelvstendigNæringsdrivende() =
        MedlemskapsregelResultat(
            listOf(
                Vilkårsutfall(
                    vilkårskode = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                    utfall = Utfall.Oppfylt,
                    utledetFakta = UtledetFakta.Ingen,
                    lovreferanse = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER.lovreferanse,
                ),
            ),
        )
}

internal data class MedlemskapsregelResultat(
    val vilkårsutfall: List<Vilkårsutfall>,
) {
    init {
        require(vilkårsutfall.isNotEmpty()) { "En regel må ha prøvd minst ett vilkår" }
    }
}

internal data class Vilkårsutfall(
    val vilkårskode: Vilkårskode,
    val utfall: Utfall,
    val utledetFakta: UtledetFakta,
    val lovreferanse: Lovreferanse,
)
