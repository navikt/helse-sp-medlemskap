package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.helse.Periode
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
    yrkesaktivitetstype: String = "ARBEIDSTAKER",
    id: UUID = UUID.randomUUID(),
) = """
    {
      "@event_name": "behov",
      "@id": "$id",
      "@opprettet": "2018-02-01T12:00:00",
      "@behov": ["Medlemskapsvurdering"],
      "fødselsnummer": "$fødselsnummer",
      "vedtaksperiodeId": "$vedtaksperiodeId",
      "yrkesaktivitetstype": "$yrkesaktivitetstype",
      "Medlemskapsvurdering": {
        "skjæringstidspunkt": "$skjæringstidspunkt",
        "medlemskapPeriodeFom": "$fom",
        "medlemskapPeriodeTom": "$tom"
      }
    }
    """

/** Sender meldingen på topicet der medlemskapstjenesten sender forhåndsvurderinger. */
internal fun TestRapid.sendForhåndsvurdering(
    @Language("JSON") melding: String,
) = sendTestMessage(melding, "nøkkel", ForhåndsvurderingRiver.TOPIC)

/**
 * Slik medlemskapstjenesten sender en forhåndsvurdering av en søknad på `medlemskap.medlemskapsvurderinger`.
 * Er [gyldigFor] null, sendes ingen datoer.
 */
@Language("JSON")
internal fun forhåndsvurderingMelding(
    søknadId: Any = UUID.randomUUID(),
    gyldigFor: Periode?,
    speilSvar: String = "JA",
    fødselsnummer: String = FØDSELSNUMMER,
    @Language("JSON") aarsaker: String = "[]",
) = """
    {
      "soknadId": "$søknadId",
      ${gyldigFor?.let { "\"gyldigForSkjaeringstidspunktFom\": \"${it.start}\", \"gyldigForSkjaeringstidspunktTom\": \"${it.endInclusive}\"," } ?: ""}
      "fnr": "$fødselsnummer",
      "speilSvar": "$speilSvar",
      "aarsaker": $aarsaker
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

/** Løser et `Medlemskap`-behov slik sparkel-medlemskap og behovsakkumulatoren gjør det. [aarsaker] er rå JSON. */
internal fun løsMedlemskapsbehov(
    behov: JsonNode,
    svar: String,
    @Language("JSON") aarsaker: String? = null,
): String {
    val løst = behov.deepCopy() as ObjectNode
    val resultat =
        løst
            .putObject("@løsning")
            .putObject("Medlemskap")
            .putObject("resultat")
            .put("svar", svar)
    if (aarsaker != null) resultat.set("aarsaker", objectMapper.readTree(aarsaker))
    løst.put("@final", true)
    return objectMapper.writeValueAsString(løst)
}
