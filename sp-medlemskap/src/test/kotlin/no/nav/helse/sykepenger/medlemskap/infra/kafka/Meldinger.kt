package no.nav.helse.sykepenger.medlemskap.infra.kafka

import org.intellij.lang.annotations.Language
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate
import java.util.UUID

internal const val FØDSELSNUMMER = "12029240045"

private val objectMapper = jacksonObjectMapper()

/** Slik Spleis ber om en medlemskapsvurdering. */
@Language("JSON")
internal fun medlemskapsvurderingBehov(
    skjæringstidspunkt: LocalDate,
    fom: LocalDate,
    tom: LocalDate,
    fødselsnummer: String = FØDSELSNUMMER,
    vedtaksperiodeId: UUID = UUID.randomUUID(),
) = """
    {
      "@event_name": "behov",
      "@id": "${UUID.randomUUID()}",
      "@opprettet": "2018-02-01T12:00:00",
      "@behov": ["Medlemskapsvurdering"],
      "fødselsnummer": "$fødselsnummer",
      "vedtaksperiodeId": "$vedtaksperiodeId",
      "Medlemskapsvurdering": {
        "skjæringstidspunkt": "$skjæringstidspunkt",
        "medlemskapPeriodeFom": "$fom",
        "medlemskapPeriodeTom": "$tom"
      }
    }
    """

/** Slik Spleis ber om resultatet av en medlemskapsvurdering. */
@Language("JSON")
internal fun medlemskapsvurderingResultatBehov(
    medlemskapsvurderingId: Any,
    fødselsnummer: String = FØDSELSNUMMER,
) = """
    {
      "@event_name": "behov",
      "@id": "${UUID.randomUUID()}",
      "@opprettet": "2018-02-01T12:00:00",
      "@behov": ["MedlemskapsvurderingResultat"],
      "fødselsnummer": "$fødselsnummer",
      "MedlemskapsvurderingResultat": {
        "medlemskapsvurderingId": "$medlemskapsvurderingId"
      }
    }
    """

/** Løser et `Medlemskap`-behov slik sparkel-medlemskap og behovsakkumulatoren gjør det. */
internal fun løsMedlemskapsbehov(
    behov: JsonNode,
    svar: String,
): String {
    val løst = behov.deepCopy() as ObjectNode
    løst
        .putObject("@løsning")
        .putObject("Medlemskap")
        .putObject("resultat")
        .put("svar", svar)
    løst.put("@final", true)
    return objectMapper.writeValueAsString(løst)
}
