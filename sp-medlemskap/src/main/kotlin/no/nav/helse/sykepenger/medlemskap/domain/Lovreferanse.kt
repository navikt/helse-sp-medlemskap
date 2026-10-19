package no.nav.helse.sykepenger.medlemskap.domain

import no.nav.helse.januar
import java.time.LocalDate

internal data class Lovreferanse(
    val lov: String,
    val paragraf: String,
    val avsnitt: Int?,
    val setning: Int?,
    val bokstav: String?,
    val iKraftFra: LocalDate,
) {
    companion object {
        fun `§ 8-2 første avsnitt, første setning`(): Lovreferanse =
            Lovreferanse(
                lov = "folketrygdloven",
                paragraf = "8-2",
                avsnitt = 1,
                setning = 1,
                bokstav = null,
                iKraftFra = 1.januar(2004),
            )
    }
}
