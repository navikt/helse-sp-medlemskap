package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageProblems
import io.micrometer.core.instrument.MeterRegistry
import no.nav.helse.sykepenger.medlemskap.domain.Årsak
import no.nav.helse.sykepenger.medlemskap.infra.kafka.Medlemskapsbehov.aarsaker
import no.nav.helse.sykepenger.medlemskap.infra.kafka.Medlemskapsbehov.løsning
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class MedlemskapsbehovTest {
    @Test
    fun `leser hver årsak med regel og forklaring`() {
        assertEquals(
            listOf(Årsak("1", "Første forklaring"), Årsak("2", "Andre forklaring")),
            lesGyldige(
                """
                [
                  {"regelId": "1", "forklaringPaaUavklart": "Første forklaring"},
                  {"regelId": "2", "forklaringPaaUavklart": "Andre forklaring"}
                ]
                """,
            ),
        )
    }

    @Test
    fun `manglende årsaker gir null`() {
        assertNull(lesGyldige(null))
    }

    @Test
    fun `årsaker som er null gir null`() {
        assertNull(lesGyldige("null"))
    }

    @Test
    fun `tom liste gir tom liste`() {
        assertEquals(emptyList(), lesGyldige("[]"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{}",
            "\"tekst\"",
            "42",
            "true",
            "[null]",
            "[\"tekst\"]",
            "[42]",
            "[[]]",
            "[{}]",
            """[{"regelId": "1"}]""",
            """[{"forklaringPaaUavklart": "Forklaring"}]""",
            """[{"regelId": null, "forklaringPaaUavklart": "Forklaring"}]""",
            """[{"regelId": 1, "forklaringPaaUavklart": "Forklaring"}]""",
            """[{"regelId": [], "forklaringPaaUavklart": "Forklaring"}]""",
            """[{"regelId": "1", "forklaringPaaUavklart": null}]""",
            """[{"regelId": "1", "forklaringPaaUavklart": false}]""",
            """[{"regelId": "1", "forklaringPaaUavklart": {}}]""",
            """[{"regelId": "1", "forklaringPaaUavklart": "Forklaring"}, {}]""",
        ],
    )
    fun `ugyldige årsaker avvises før onPacket`(aarsaker: String) {
        val resultat = send(aarsaker)
        assertEquals(Resultat.Avvist, resultat)
    }

    private fun lesGyldige(aarsaker: String?): List<Årsak>? {
        val resultat = send(aarsaker)
        check(resultat is Resultat.Mottatt) { "Forventet at meldingen ble mottatt, men den ble $resultat" }
        return resultat.aarsaker
    }

    private sealed interface Resultat {
        data class Mottatt(
            val aarsaker: List<Årsak>?,
        ) : Resultat

        data object Avvist : Resultat
    }

    private fun send(aarsaker: String?): Resultat? {
        val rapid = TestRapid()
        var resultat: Resultat? = null
        River(rapid).løsning {}.register(
            object : River.PacketListener {
                override fun onPacket(
                    packet: JsonMessage,
                    context: MessageContext,
                    metadata: MessageMetadata,
                    meterRegistry: MeterRegistry,
                ) {
                    resultat = Resultat.Mottatt(packet.aarsaker())
                }

                override fun onError(
                    problems: MessageProblems,
                    context: MessageContext,
                    metadata: MessageMetadata,
                ) {
                    resultat = Resultat.Avvist
                }
            },
        )
        val aarsakerfelt = aarsaker?.let { """, "aarsaker": $it""" } ?: ""
        rapid.sendTestMessage(
            """
            {
              "@event_name": "behov",
              "@behov": ["Medlemskap"],
              "@final": true,
              "fødselsnummer": "$FØDSELSNUMMER",
              "medlemskapsprøvingId": "${UUID.randomUUID()}",
              "skjæringstidspunkt": "2018-02-01",
              "@løsning": {
                "Medlemskap": {
                  "resultat": {"svar": "UAVKLART" $aarsakerfelt}
                }
              }
            }
            """,
        )
        return resultat
    }
}
