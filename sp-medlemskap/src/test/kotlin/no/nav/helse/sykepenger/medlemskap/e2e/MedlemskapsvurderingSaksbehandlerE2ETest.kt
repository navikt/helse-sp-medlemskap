package no.nav.helse.sykepenger.medlemskap.e2e

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.medlemskap.e2e.E2ETestapp.Companion.SAKSBEHANDLER
import no.nav.helse.sykepenger.medlemskap.e2e.E2ETestapp.Companion.løsMedlemskapsbehov
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

    @Test
    fun `ny automatisk vurdering spør medlemskapstjenesten på nytt og varsler Spleis om endringen`() =
        testApplication {
            val app = E2ETestapp()
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)
            application { with(app) { settOpp(pseudoIdProvider) } }

            val første = app.vurderAutomatisk("UAVKLART_MED_BRUKERSPORSMAAL")
            val antallFør = app.rapid.inspektør.size

            val postRespons = client.vurderPåNytt(pseudoId)
            assertEquals(HttpStatusCode.OK, postRespons.status)
            val postJson = objectMapper.readTree(postRespons.bodyAsText())
            assertEquals("VENTER_PÅ_GRUNNLAG", postJson["status"].asString())

            val behov = app.meldinger.last()
            assertEquals(antallFør + 1, app.rapid.inspektør.size)
            assertEquals(listOf("Medlemskap"), behov.path("@behov").toList().map { it.asString() })
            assertEquals(postJson["medlemskapsprøvingId"].asString(), behov.path("medlemskapsprøvingId").asString())
            assertEquals(SAKSBEHANDLER, behov.path("saksbehandlerIdent").asString())
            assertEquals("2018-02-01", behov.path("Medlemskap").path("medlemskapPeriodeFom").asString())
            assertEquals("2018-02-28", behov.path("Medlemskap").path("medlemskapPeriodeTom").asString())
            assertFalse(behov.has("opprinneligBehov"))

            // Mens prøvingen pågår kan det ikke startes enda en
            assertEquals(HttpStatusCode.Conflict, client.vurderPåNytt(pseudoId).status)

            app.rapid.sendTestMessage(løsMedlemskapsbehov(behov, "JA"), FØDSELSNUMMER)
            assertEquals(antallFør + 1, app.rapid.inspektør.size, "Ny vurdering skal ikke svare på noe behov direkte")

            app.outboxJobb.kjørEnRunde()
            val (handling, endret) = app.meldinger.drop(antallFør + 1)
            assertEquals("NY_AUTOMATISK_MEDLEMSKAPSVURDERING", handling.path("handlingType").asString())
            assertEquals(SAKSBEHANDLER, handling.path("saksbehandlerIdent").asString())
            assertFalse(handling.path("kreverTotrinnsvurdering").asBoolean())
            assertEquals("endret_medlemskapsvurdering", endret.path("@event_name").asString())
            assertFalse(endret.path("manuellVurdering").asBoolean())
            val ny = endret.path("medlemskapsvurderingId").asString()
            assertNotEquals(første, ny)
            assertEquals(ny, handling.path("handlingId").asString())
            assertEquals("JA", app.resultat(ny).path("svar").asString())

            assertEquals(HttpStatusCode.OK, client.vurderPåNytt(pseudoId).status)
        }

    @Test
    fun `ny automatisk vurdering uten eksisterende vurdering gir 404`() =
        testApplication {
            val app = E2ETestapp()
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)
            application { with(app) { settOpp(pseudoIdProvider) } }

            assertEquals(HttpStatusCode.NotFound, client.vurderPåNytt(pseudoId).status)
            app.outboxJobb.kjørEnRunde()
            assertEquals(0, app.rapid.inspektør.size)
        }

    private suspend fun HttpClient.vurderPåNytt(pseudoId: Any): HttpResponse =
        post("/api/personer/$pseudoId/vilkarsvurderinger/automatisk") {
            contentType(ContentType.Application.Json)
            setBody("""{ "skjæringstidspunkt": "2018-02-01" }""")
        }
}
