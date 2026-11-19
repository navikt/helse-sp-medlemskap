package no.nav.helse.sykepenger.medlemskap.e2e

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.medlemskap.infra.db.DatabaseTest
import no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures.InMemoryPersonPseudoIdProvider
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Test
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
