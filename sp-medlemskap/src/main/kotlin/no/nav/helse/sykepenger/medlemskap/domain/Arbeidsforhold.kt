package no.nav.helse.sykepenger.medlemskap.domain

import no.nav.helse.Periode
import no.nav.helse.til
import java.time.LocalDate

internal data class Arbeidsforhold(
    val orgnummer: String,
    val ansettelseperiode: Periode,
    val type: Arbeidsforholdtype,
) {
    enum class Arbeidsforholdtype {
        FORENKLET_OPPGJØRSORDNING,
        FRILANSER,
        MARITIMT,
        ORDINÆRT,

        // Brukes når kilden (f.eks. spleis) ikke oppgir reell arbeidsforholdtype.
        UKJENT,
    }

    constructor(orgnummer: String, ansattFom: LocalDate, ansattTom: LocalDate? = null, type: Arbeidsforholdtype) : this(orgnummer, ansattFom til (ansattTom ?: LocalDate.MAX), type)

    init {
        check(orgnummer.isNotBlank())
    }
}
