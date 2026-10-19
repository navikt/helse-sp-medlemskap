package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.helse.sykepenger.medlemskap.application.InMemoryTransaksjonProvider
import no.nav.helse.sykepenger.medlemskap.application.PersonAvstemmingService
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal class PersonAvstemmingRiverTest {
    private val transaksjon = InMemoryTransaksjonProvider()
    private val rapid =
        TestRapid().apply {
            PersonAvstemmingRiver(
                rapidsConnection = this,
                personAvstemmingService =
                    PersonAvstemmingService(
                        transaksjonProvider = transaksjon,
                        spleisClient =
                            object : ISpleisClient {
                                override fun hentMedlemskapsvurderinger(fødselsnummer: String) =
                                    listOf(
                                        SpleisMedlemskapsvurdering.Spleis(
                                            medlemskapsvurderingId = MedlemskapsvurderingId(UUID.randomUUID()),
                                            opprettet = Instant.now(),
                                            skjæringstidspunkt = LocalDate.of(2026, 1, 1),
                                            status = Medlemskapsstatus.Ja,
                                        ),
                                    )
                            },
                    ),
            )
        }

    @Test
    fun `lagrer medlemskapsvurderinger ved person-avstemming`() {
        rapid.sendTestMessage(
            """
            {
              "@event_name": "person_avstemming",
              "@id": "${UUID.randomUUID()}",
              "fødselsnummer": "$FØDSELSNUMMER"
            }
            """.trimIndent(),
        )

        assertEquals(1, transaksjon.medlemskapsvurderinger.antallLagringer)
        assertEquals(
            FØDSELSNUMMER,
            transaksjon.medlemskapsvurderinger.alleVurderinger
                .single()
                .fødselsnummer,
        )
    }

    @Test
    fun `ignorerer andre hendelser`() {
        rapid.sendTestMessage(
            """
            {
              "@event_name": "annen-hendelse",
              "@id": "${UUID.randomUUID()}",
              "fødselsnummer": "$FØDSELSNUMMER"
            }
            """.trimIndent(),
        )

        assertEquals(0, transaksjon.medlemskapsvurderinger.antallLagringer)
    }

    private companion object {
        const val FØDSELSNUMMER = "12029240045"
    }
}
