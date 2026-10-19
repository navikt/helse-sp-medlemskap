package no.nav.helse.sykepenger.medlemskap.leaderelection

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class NaisLeaderElectionTest {
    private val elector = WireMockServer(wireMockConfig().dynamicPort()).also(WireMockServer::start)
    private val leaderElection =
        NaisLeaderElection(
            electorGetUrl = "${elector.baseUrl()}/",
            hostname = "sp-medlemskap-abc123",
        )

    @BeforeEach
    fun beforeEach() {
        elector.resetAll()
    }

    @AfterAll
    fun afterAll() {
        elector.stop()
    }

    @Test
    fun `er leader når elector svarer med vårt hostname`() {
        elector.stubFor(
            get(urlPathEqualTo("/")).willReturn(
                okJson("""{"name":"sp-medlemskap-abc123","last_update":"2026-09-29T14:00:00Z"}"""),
            ),
        )

        assertTrue(leaderElection.isLeader())
    }

    @Test
    fun `er ikke leader når elector svarer med en annen pod`() {
        elector.stubFor(
            get(urlPathEqualTo("/")).willReturn(
                okJson("""{"name":"sp-medlemskap-def456","last_update":"2026-09-29T14:00:00Z"}"""),
            ),
        )

        assertFalse(leaderElection.isLeader())
    }

    @Test
    fun `er ikke leader når elector svarer med feil`() {
        elector.stubFor(get(urlPathEqualTo("/")).willReturn(aResponse().withStatus(503)))

        assertFalse(leaderElection.isLeader())
    }

    @Test
    fun `er ikke leader når elector svarer uten navn`() {
        elector.stubFor(get(urlPathEqualTo("/")).willReturn(okJson("{}")))

        assertFalse(leaderElection.isLeader())
    }
}
