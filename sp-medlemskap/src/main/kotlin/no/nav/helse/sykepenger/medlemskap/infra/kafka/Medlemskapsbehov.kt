package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers.asLocalDate
import no.nav.helse.Periode
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import tools.jackson.databind.JsonNode
import java.time.LocalDate

/**
 * Behovet `Medlemskap` løses av sparkel-medlemskap, som spør medlemskapstjenesten (LovMe) om
 * personen var medlem i folketrygden i perioden `medlemskapPeriodeFom`–`medlemskapPeriodeTom`.
 * Løsningen ligger i `@løsning.Medlemskap.resultat.svar`.
 *
 * sparkel-medlemskap krever `vedtaksperiodeId` på behovet (den brukes bare til logging).
 */
internal object Medlemskapsbehov {
    const val NAVN = "Medlemskap"
    private const val SVARFELT = "@løsning.$NAVN.resultat.svar"

    // https://github.com/navikt/medlemskap-sykepengerlytter/blob/e4abb7bd5796357e9fe9bc1e6da77506f761c560/src/main/kotlin/no/nav/medlemskap/sykepenger/lytter/speil_medlemskapsvurdering/SpeilRespons.kt#L5-L10
    private val svar =
        mapOf(
            "JA" to Medlemskapsstatus.Ja,
            "NEI" to Medlemskapsstatus.Nei,
            "UAVKLART" to Medlemskapsstatus.Uavklart,
            "UAVKLART_MED_BRUKERSPORSMAAL" to Medlemskapsstatus.UavklartMedBrukerspørsmål,
        )

    fun nytt(
        fødselsnummer: String,
        vedtaksperiodeId: String,
        skjæringstidspunkt: LocalDate,
        medlemskapsprøvingId: MedlemskapsprøvingId,
        oppslagsperiode: Periode,
        ekstra: Map<String, Any> = emptyMap(),
    ): JsonMessage =
        JsonMessage.newNeed(
            behov = listOf(NAVN),
            map =
                mapOf(
                    "fødselsnummer" to fødselsnummer,
                    "vedtaksperiodeId" to vedtaksperiodeId,
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                    "medlemskapsprøvingId" to medlemskapsprøvingId.toString(),
                    NAVN to
                        mapOf(
                            "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                            "medlemskapPeriodeFom" to oppslagsperiode.start.toString(),
                            "medlemskapPeriodeTom" to oppslagsperiode.endInclusive.toString(),
                        ),
                ) + ekstra,
        )

    /** Felles krav til en ferdig løsning på et `Medlemskap`-behov sendt ut av sp-medlemskap. */
    fun River.løsning(ekstraPrecondition: (JsonMessage) -> Unit) =
        apply {
            precondition {
                it.requireValue("@event_name", "behov")
                it.requireAllOrAny("@behov", listOf(NAVN))
                it.requireValue("@final", true)
                it.requireKey("fødselsnummer", "medlemskapsprøvingId", "@løsning")
                ekstraPrecondition(it)
            }
            validate {
                it.require("skjæringstidspunkt", JsonNode::asLocalDate)
                it.requireAny(SVARFELT, svar.keys.toList())
            }
        }

    fun JsonMessage.medlemskapsstatus(): Medlemskapsstatus = svar.getValue(this[SVARFELT].asString())

    fun JsonMessage.medlemskapsprøvingId() = MedlemskapsprøvingId(this["medlemskapsprøvingId"].asUUID())
}
