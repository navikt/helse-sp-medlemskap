package no.nav.helse.sykepenger.medlemskap.e2e

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.medlemskap.infra.db.DatabaseTest
import no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures.InMemoryPersonPseudoIdProvider
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class MedlemskapsvurderingSaksbehandlerE2ETest : DatabaseTest() {
    private val objectMapper = jacksonObjectMapper()
    private val identitetsnummer = Identitetsnummer(FØDSELSNUMMER)

    @Test
    fun `saksbehandler overstyrer nei til ja, og Spleis og Spesialist får beskjed`() =
        testApplication {
            val app = E2ETestapp()
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)
            application { with(app) { settOpp(pseudoIdProvider) } }

            val automatisk = app.vurderAutomatisk("NEI")
            assertFalse(app.resultat(automatisk).path("ok").asBoolean())
            val antallFørManuell = app.rapid.inspektør.size

            @Language("JSON")
            val request = """
                {
                  "skjæringstidspunkt": "2018-02-01",
                  "status": "JA",
                  "fritekstbegrunnelse": "Dokumentert medlemskap",
                  "journalpostId": ["journalpost-1"]
                }
                """
            val postRespons =
                client.post("/api/personer/$pseudoId/vilkarsvurderinger/manuell") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            assertEquals(HttpStatusCode.OK, postRespons.status)
            val manuell = objectMapper.readTree(postRespons.bodyAsText())["medlemskapsvurderingId"].asString()
            assertNotEquals(automatisk, manuell)

            app.outboxJobb.kjørEnRunde()
            val (handling, endret) = app.meldinger.drop(antallFørManuell)
            assertEquals("saksbehandlerhandling", handling.path("@event_name").asString())
            assertEquals("MANUELT_VURDERT_MEDLEMSKAP", handling.path("handlingType").asString())
            assertTrue(handling.path("kreverTotrinnsvurdering").asBoolean())
            assertEquals(manuell, handling.path("handlingId").asString())
            assertEquals("endret_medlemskapsvurdering", endret.path("@event_name").asString())
            assertEquals(manuell, endret.path("medlemskapsvurderingId").asString())
            assertTrue(endret.path("manuellVurdering").asBoolean())

            val resultat = app.resultat(manuell)
            assertEquals("JA", resultat.path("svar").asString())
            assertTrue(resultat.path("ok").asBoolean())

            val krav = objectMapper.readTree(client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=$manuell").bodyAsText())["krav"].single()
            assertEquals("SAKSBEHANDLER", krav["kilde"]["kildetype"].asString())
            assertEquals("Dokumentert medlemskap", krav["kilde"]["fritekstbegrunnelse"].asString())

            // Spleis spør på nytt for samme skjæringstidspunkt og får den manuelle vurderingen
            app.rapid.sendTestMessage(E2ETestapp.medlemskapsvurderingBehov(java.util.UUID.randomUUID()), FØDSELSNUMMER)
            assertEquals(
                manuell,
                app.meldinger
                    .last()
                    .path("@løsning")
                    .path("Medlemskapsvurdering")
                    .path("id")
                    .asString(),
            )
        }

    @ParameterizedTest(name = "automatisk {0}, manuell {1} gir kreverTotrinnsvurdering={2}")
    @CsvSource(
        "JA, JA, false",
        "JA, NEI, true",
        "NEI, JA, true",
        "NEI, NEI, false",
        "UAVKLART, JA, false",
        "UAVKLART, NEI, true",
        "UAVKLART_MED_BRUKERSPORSMAAL, JA, false",
        "UAVKLART_MED_BRUKERSPORSMAAL, NEI, true",
    )
    fun `totrinnsvurdering kreves bare når manuell vurdering endrer om det blir utbetaling`(
        automatiskSvar: String,
        manuellStatus: String,
        forventetTotrinn: Boolean,
    ) = testApplication {
        val app = E2ETestapp()
        val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
        val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)
        application { with(app) { settOpp(pseudoIdProvider) } }

        app.vurderAutomatisk(automatiskSvar)

        assertEquals(forventetTotrinn, vurderManuelt(app, pseudoId, manuellStatus).path("kreverTotrinnsvurdering").asBoolean())
    }

    @Test
    fun `manuell vurdering uten tidligere vurdering krever ikke totrinnsvurdering`() =
        testApplication {
            val app = E2ETestapp()
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)
            application { with(app) { settOpp(pseudoIdProvider) } }

            assertFalse(vurderManuelt(app, pseudoId, "NEI").path("kreverTotrinnsvurdering").asBoolean())
        }

    @Test
    fun `ny manuell vurdering sammenlignes med forrige manuelle vurdering`() =
        testApplication {
            val app = E2ETestapp()
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)
            application { with(app) { settOpp(pseudoIdProvider) } }

            app.vurderAutomatisk("JA")
            assertTrue(vurderManuelt(app, pseudoId, "NEI").path("kreverTotrinnsvurdering").asBoolean())
            assertFalse(vurderManuelt(app, pseudoId, "NEI").path("kreverTotrinnsvurdering").asBoolean())
            assertTrue(vurderManuelt(app, pseudoId, "JA").path("kreverTotrinnsvurdering").asBoolean())
        }

    @Test
    fun `vurdering på et annet skjæringstidspunkt påvirker ikke totrinnsvurdering`() =
        testApplication {
            val app = E2ETestapp()
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)
            application { with(app) { settOpp(pseudoIdProvider) } }

            app.vurderAutomatisk("JA", skjæringstidspunkt = "2018-01-01")

            assertFalse(vurderManuelt(app, pseudoId, "NEI").path("kreverTotrinnsvurdering").asBoolean())
        }

    /** Poster en manuell vurdering for 2018-02-01 og gir saksbehandlerhandlingen som publiseres fra outboxen. */
    private suspend fun ApplicationTestBuilder.vurderManuelt(
        app: E2ETestapp,
        pseudoId: Any,
        status: String,
    ): JsonNode {
        val antallFør = app.rapid.inspektør.size
        val respons =
            client.post("/api/personer/$pseudoId/vilkarsvurderinger/manuell") {
                contentType(ContentType.Application.Json)
                setBody("""{"skjæringstidspunkt": "2018-02-01", "status": "$status", "fritekstbegrunnelse": "x", "journalpostId": []}""")
            }
        assertEquals(HttpStatusCode.OK, respons.status)
        app.outboxJobb.kjørEnRunde()
        return app.meldinger.drop(antallFør).single { it.path("@event_name").asString() == "saksbehandlerhandling" }
    }

    @Test
    fun `saksbehandler kan ikke la medlemskap være uavklart`() =
        testApplication {
            val app = E2ETestapp()
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)
            application { with(app) { settOpp(pseudoIdProvider) } }

            val respons =
                client.post("/api/personer/$pseudoId/vilkarsvurderinger/manuell") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"skjæringstidspunkt": "2018-02-01", "status": "UAVKLART", "fritekstbegrunnelse": "x", "journalpostId": []}""")
                }

            assertEquals(HttpStatusCode.BadRequest, respons.status)
        }
}
