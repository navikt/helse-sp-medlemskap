package no.nav.helse.sykepenger.medlemskap.infra.spleis

import com.github.navikt.tbd_libs.access_token.AccessTokenProvider
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.*

internal class SpleisClientTest {
    private val server = WireMockServer(wireMockConfig().dynamicPort())
    private val scope = "api://dev-gcp.tbd.spleis-api/.default"
    private val fakeToken = "et-fake-token"
    private val klokka12 = Instant.parse("2026-09-29T10:00:00Z") // Oslo
    private val klokka11 = Instant.parse("2026-09-29T09:00:00Z") // Oslo
    private val tokenProvider =
        object : AccessTokenProvider {
            override fun machineToken(scope: String) = fakeToken

            override fun oboToken(
                accessToken: String,
                scope: String,
            ): String = throw NotImplementedError("ikke i bruk i denne testen")
        }
    private lateinit var client: SpleisClient

    @BeforeEach
    fun setUp() {
        server.start()
        client =
            SpleisClient(
                scope = scope,
                baseUrl = server.baseUrl(),
                tokenProvider = tokenProvider,
                timeout = Duration.ofMillis(200),
                ventetidMellomForsøk = Duration.ZERO,
            )
    }

    @AfterEach
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `prøver på nytt ved timeout`() {
        server.stubFor(
            post(urlEqualTo("/api/medlemskapsvurderinger"))
                .inScenario("timeout")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withFixedDelay(1000).withBody(enRespons))
                .willSetStateTo("svarer"),
        )
        server.stubFor(
            post(urlEqualTo("/api/medlemskapsvurderinger"))
                .inScenario("timeout")
                .whenScenarioStateIs("svarer")
                .willReturn(aResponse().withStatus(200).withBody(enRespons)),
        )

        val medlemskapsvurderinger = client.hentMedlemskapsvurderinger("11111111111")

        assertEquals(4, medlemskapsvurderinger.size)
        server.verify(2, postRequestedFor(urlEqualTo("/api/medlemskapsvurderinger")))
    }

    @Test
    fun `gir opp etter maks antall forsøk ved timeout`() {
        server.stubFor(
            post(urlEqualTo("/api/medlemskapsvurderinger"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(1000).withBody(enRespons)),
        )

        assertThrows<HttpTimeoutException> {
            client.hentMedlemskapsvurderinger("11111111111")
        }
        server.verify(3, postRequestedFor(urlEqualTo("/api/medlemskapsvurderinger")))
    }

    @Test
    fun `henter og tolker medlemskapsvurderinger fra spleis`() {
        server.stubFor(
            post(urlEqualTo("/api/medlemskapsvurderinger"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(enRespons),
                ),
        )

        val medlemskapsvurderinger = client.hentMedlemskapsvurderinger("11111111111")

        assertEquals(4, medlemskapsvurderinger.size)

        assertEquals(
            SpleisMedlemskapsvurdering.Infotrygd(
                medlemskapsvurderingId = MedlemskapsvurderingId(UUID.fromString("b89e2ae5-59e3-388e-98cd-42a8e7350773")),
                skjæringstidspunkt = LocalDate.of(2018, 1, 1),
                opprettet = klokka12,
            ),
            medlemskapsvurderinger[0],
        )
        assertEquals(
            listOf(Medlemskapsstatus.Nei, Medlemskapsstatus.Ja, Medlemskapsstatus.Uavklart),
            medlemskapsvurderinger.drop(1).map { (it as SpleisMedlemskapsvurdering.Spleis).status },
        )
        assertEquals(klokka11, medlemskapsvurderinger[2].opprettet)
        assertEquals(LocalDate.of(2018, 4, 1), medlemskapsvurderinger[3].skjæringstidspunkt)

        server.verify(
            postRequestedFor(urlEqualTo("/api/medlemskapsvurderinger"))
                .withHeader("Authorization", equalTo("Bearer $fakeToken"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""{"fødselsnummer":"11111111111"}""")),
        )
    }

    @Test
    fun `kaster exception ved feilrespons fra spleis`() {
        server.stubFor(
            post(urlEqualTo("/api/medlemskapsvurderinger"))
                .willReturn(aResponse().withStatus(500).withBody("noe gikk galt")),
        )

        assertThrows<SpleisClientException> {
            client.hentMedlemskapsvurderinger("11111111111")
        }
    }

    @Test
    fun `kaster exception når spleis-vurdering mangler medlemskapstatus`() {
        server.stubFor(
            post(urlEqualTo("/api/medlemskapsvurderinger"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """
                            {
                              "medlemskapsvurderinger": [
                                {
                                  "medlemskapsvurderingId": "b89e2ae5-59e3-388e-98cd-42a8e7350773",
                                  "opprettet": "2026-09-29T12:00:00.000000",
                                  "skjæringstidspunkt": "2018-01-01",
                                  "kilde": "SPLEIS"
                                }
                              ]
                            }
                            """.trimIndent(),
                        ),
                ),
        )

        assertThrows<IllegalStateException> {
            client.hentMedlemskapsvurderinger("11111111111")
        }
    }

    @Language("JSON")
    private val enRespons = """
            {
              "medlemskapsvurderinger": [
                {
                  "medlemskapsvurderingId": "b89e2ae5-59e3-388e-98cd-42a8e7350773",
                  "opprettet": "2026-09-29T12:00:00.000000",
                  "skjæringstidspunkt": "2018-01-01",
                  "kilde": "INFOTRYGD"
                },
                {
                  "medlemskapsvurderingId": "00000000-0000-0000-0000-000000000001",
                  "opprettet": "2026-09-29T12:00:00.000000",
                  "skjæringstidspunkt": "2018-04-01",
                  "kilde": "SPLEIS",
                  "medlemskapstatus": "NEI"
                },
                {
                  "medlemskapsvurderingId": "00000000-0000-0000-0000-000000000002",
                  "opprettet": "2026-09-29T11:00:00.000000",
                  "skjæringstidspunkt": "2018-04-01",
                  "kilde": "SPLEIS",
                  "medlemskapstatus": "JA"
                },
                {
                  "medlemskapsvurderingId": "00000000-0000-0000-0000-000000000003",
                  "opprettet": "2026-09-29T12:00:00.000000",
                  "skjæringstidspunkt": "2018-04-01",
                  "kilde": "SPLEIS",
                  "medlemskapstatus": "VET_IKKE"
                }
              ]
            }
            """
}
