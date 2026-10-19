package no.nav.helse.sykepenger.medlemskap.infra.db

import no.nav.helse.januar
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidsforhold
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidsforhold.Arbeidsforholdtype.ORDINÆRT
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsgrunnlag
import java.time.LocalDate

internal const val FØDSELSNUMMER = "12029240045"
internal const val ORGNUMMER = "987654321"

internal fun arbeidstakergrunnlag(vararg arbeidsforhold: Arbeidsforhold) =
    Medlemskapsgrunnlag.Arbeidstaker(
        arbeidsforhold.toList().ifEmpty { listOf(arbeidsforhold(ansattFom = 1.januar, ansattTom = 31.januar)) },
    )

internal fun arbeidsforhold(
    orgnummer: String = ORGNUMMER,
    ansattFom: LocalDate,
    ansattTom: LocalDate? = null,
    type: Arbeidsforhold.Arbeidsforholdtype = ORDINÆRT,
) = Arbeidsforhold(orgnummer = orgnummer, ansattFom = ansattFom, ansattTom = ansattTom, type = type)
