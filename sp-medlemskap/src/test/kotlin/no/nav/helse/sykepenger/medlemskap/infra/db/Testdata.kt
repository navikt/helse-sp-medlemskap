package no.nav.helse.sykepenger.medlemskap.infra.db

import no.nav.helse.februar
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsprøving
import no.nav.helse.til
import java.time.LocalDate

internal const val FØDSELSNUMMER = "12029240045"
internal const val ANNET_FØDSELSNUMMER = "12029240046"

internal val OPPSLAGSPERIODE = 1.februar til 28.februar

internal fun nyPrøving(
    fødselsnummer: String = FØDSELSNUMMER,
    skjæringstidspunkt: LocalDate = 1.februar,
) = Medlemskapsprøving.start(fødselsnummer, skjæringstidspunkt, OPPSLAGSPERIODE)
