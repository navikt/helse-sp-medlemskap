package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.asLocalDate
import com.github.navikt.tbd_libs.rapids_and_rivers.asOptionalLocalDate
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidsforhold
import tools.jackson.databind.JsonNode

internal fun JsonNode.tilArbeidsforhold(): List<Arbeidsforhold> =
    this
        .filterNot { it["orgnummer"].asString().isBlank() }
        .filter {
            val til = it["ansattTil"].asOptionalLocalDate()
            til == null || it["ansattSiden"].asLocalDate() <= til
        }.map {
            Arbeidsforhold(
                orgnummer = it["orgnummer"].asString(),
                ansattFom = it["ansattSiden"].asLocalDate(),
                ansattTom = it["ansattTil"].asOptionalLocalDate(),
                type =
                    when (it["type"].asString()) {
                        "FORENKLET_OPPGJØRSORDNING" -> Arbeidsforhold.Arbeidsforholdtype.FORENKLET_OPPGJØRSORDNING
                        "FRILANSER" -> Arbeidsforhold.Arbeidsforholdtype.FRILANSER
                        "MARITIMT" -> Arbeidsforhold.Arbeidsforholdtype.MARITIMT
                        "ORDINÆRT" -> Arbeidsforhold.Arbeidsforholdtype.ORDINÆRT
                        else -> error("har ikke mappingregel for arbeidsforholdtype: ${it["type"].asString()}")
                    },
            )
        }
