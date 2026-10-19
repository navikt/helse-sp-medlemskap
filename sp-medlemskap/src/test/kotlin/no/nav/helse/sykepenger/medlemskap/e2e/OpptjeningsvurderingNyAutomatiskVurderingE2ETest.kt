package no.nav.helse.sykepenger.medlemskap.e2e

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.auth.authentication
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.medlemskap.application.PersonAvstemmingService
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.infra.db.Database
import no.nav.helse.sykepenger.medlemskap.infra.db.DatabaseTest
import no.nav.helse.sykepenger.medlemskap.infra.kafka.GrunnlagForAutomatiskArbeidstakerMedlemskapsvurderingRiver
import no.nav.helse.sykepenger.medlemskap.infra.kafka.GrunnlagForNyArbeidstakerMedlemskapsvurderingRiver
import no.nav.helse.sykepenger.medlemskap.infra.kafka.MedlemskapsvurderingRiver
import no.nav.helse.sykepenger.medlemskap.infra.kafka.OutboxPubliseringsjobb
import no.nav.helse.sykepenger.medlemskap.infra.rest.GetVilkårsvurderingerForPersonBehandler
import no.nav.helse.sykepenger.medlemskap.infra.rest.PostNyAutomatiskVilkårsvurderingBehandler
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.leaderelection.LeaderElection
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.AccessToken
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.NavIdent
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Saksbehandler
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.SaksbehandlerOid
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.SaksbehandlerPrincipal
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Tilgang
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureContentNegotiation
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureResources
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.RestAdapter
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.get
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.post
import no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures.InMemoryPersonPseudoIdProvider
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

internal class MedlemskapsvurderingNyAutomatiskVurderingE2ETest : DatabaseTest() {
    private val transaksjon = Database.transaksjonProvider
    private val rapid =
        TestRapid().apply {
            MedlemskapsvurderingRiver(this, transaksjon)
            GrunnlagForAutomatiskArbeidstakerMedlemskapsvurderingRiver(this, transaksjon)
            GrunnlagForNyArbeidstakerMedlemskapsvurderingRiver(this, transaksjon)
        }

    private val outboxJobb =
        OutboxPubliseringsjobb(
            rapid,
            transaksjon,
            LeaderElection { true },
        )

    private val saksbehandler = Saksbehandler(NavIdent("Z999999"), SaksbehandlerOid("oid"), "Test Testesen")
    private val identitetsnummer = Identitetsnummer(FØDSELSNUMMER)
    private val objectMapper = jacksonObjectMapper()

    private fun Application.settOppTestapp(personPseudoIdProvider: InMemoryPersonPseudoIdProvider) {
        configureContentNegotiation()
        configureResources()

        val principal = SaksbehandlerPrincipal(saksbehandler, setOf(Tilgang.Les, Tilgang.Skriv), AccessToken("token"))
        intercept(ApplicationCallPipeline.Plugins) {
            call.authentication.principal(principal)
        }

        val restAdapter =
            RestAdapter<Transaksjonskontekst>(
                personPseudoIdProvider = personPseudoIdProvider,
                populasjonstilgangskontrollProvider =
                    object : PopulasjonstilgangskontrollProvider {
                        override fun kontrollerKomplettTilgang(
                            accessToken: String,
                            fødselsnummer: String,
                        ) = TilgangskontrollResultat.Ok

                        override fun kontrollerKjerneTilgang(
                            accessToken: String,
                            fødselsnummer: String,
                        ) = TilgangskontrollResultat.Ok

                        override fun kontrollerKjerneTilgangForAnsatt(
                            ansattId: String,
                            fødselsnummer: String,
                        ) = TilgangskontrollResultat.Ok
                    },
                transaksjonProvider = transaksjon,
            )
        routing {
            get(
                GetVilkårsvurderingerForPersonBehandler(
                    PersonAvstemmingService(
                        transaksjon,
                        object : ISpleisClient {
                            override fun hentMedlemskapsvurderinger(fødselsnummer: String): List<SpleisMedlemskapsvurdering> = emptyList()
                        },
                    ),
                ),
                restAdapter,
            )
            post(PostNyAutomatiskVilkårsvurderingBehandler(publiser = { key, melding -> rapid.publish(key, melding) }), restAdapter)
        }
    }

    private suspend fun HttpClient.vurderPåNytt(pseudoId: Any): HttpResponse =
        post("/api/personer/$pseudoId/vilkarsvurderinger/automatisk") {
            contentType(ContentType.Application.Json)
            setBody("""{ "skjæringstidspunkt": "2018-02-01" }""")
        }

    @Test
    fun `ny automatisk vurdering henter arbeidsforhold på nytt og varsler Spleis om endringen`() =
        testApplication {
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)
            application { settOppTestapp(pseudoIdProvider) }

            // Steg 1: første automatiske vurdering — for kort medlemskap (27 dager, krever 28)
            val behovId = UUID.randomUUID()
            rapid.sendTestMessage(medlemskapsvurderingBehov(behovId), FØDSELSNUMMER)
            rapid.sendTestMessage(
                arbeidsforholdløsningTilOpprinneligBehov(behovId, arbeidsforhold(ansattSiden = "2018-01-05", ansattTil = "2018-01-31")),
                FØDSELSNUMMER,
            )
            assertEquals(2, rapid.inspektør.size)
            val førsteId =
                rapid.inspektør
                    .message(1)
                    .path("@løsning")
                    .path("Medlemskapsvurdering")
                    .path("id")
                    .asString()

            // Steg 2: saksbehandler ber om ny automatisk vurdering
            val postRespons = client.vurderPåNytt(pseudoId)
            assertEquals(HttpStatusCode.OK, postRespons.status)
            assertEquals("VENTER_PÅ_GRUNNLAG", objectMapper.readTree(postRespons.bodyAsText())["status"].asString())

            // Steg 3: API-kallet publiserer behov for arbeidsforhold knyttet til den nye prøvingen direkte på rapiden
            assertEquals(3, rapid.inspektør.size)
            val arbeidsforholdbehov = rapid.inspektør.message(2)
            assertEquals(FØDSELSNUMMER, rapid.inspektør.key(2))
            assertEquals(FØDSELSNUMMER, arbeidsforholdbehov.path("fødselsnummer").asString())
            assertEquals("2018-02-01", arbeidsforholdbehov.path("skjæringstidspunkt").asString())

            assertEquals("Z999999", arbeidsforholdbehov.path("saksbehandlerIdent").asString())

            // Mens prøvingen pågår kan det ikke startes enda en, og ingenting publiseres
            assertEquals(HttpStatusCode.Conflict, client.vurderPåNytt(pseudoId).status)
            outboxJobb.kjørEnRunde()
            assertEquals(3, rapid.inspektør.size)
            assertEquals(listOf("ArbeidsforholdV2"), arbeidsforholdbehov.path("@behov").toList().map { it.asString() })
            assertTrue(arbeidsforholdbehov.hasNonNull("medlemskapsprøvingId"))
            assertFalse(arbeidsforholdbehov.has("opprinneligBehov"))

            // Steg 4: løsningen kommer — nå med lang nok medlemskap
            val løsning = (arbeidsforholdbehov.deepCopy() as ObjectNode)
            løsning.put("@final", true)
            løsning.set(
                "@løsning",
                objectMapper.readTree("""{ "ArbeidsforholdV2": [${arbeidsforhold(ansattSiden = "2018-01-01", ansattTil = "2018-01-31")}] }"""),
            )
            rapid.sendTestMessage(løsning.toString(), FØDSELSNUMMER)
            assertEquals(3, rapid.inspektør.size) { "Ny vurdering skal ikke svare på noe behov direkte" }

            // Steg 5: saksbehandlerhandlingen sendes til Spesialist og endringen til Spleis via outboxen
            outboxJobb.kjørEnRunde()
            assertEquals(5, rapid.inspektør.size)
            val saksbehandlerhandling = rapid.inspektør.message(3)
            val endretEvent = rapid.inspektør.message(4)
            assertEquals("endret_medlemskapsvurdering", endretEvent.path("@event_name").asString())
            assertEquals(FØDSELSNUMMER, endretEvent.path("fødselsnummer").asString())
            assertEquals("2018-02-01", endretEvent.path("skjæringstidspunkt").asString())
            assertFalse(endretEvent.path("manuellVurdering").asBoolean())
            val nyId = endretEvent.path("medlemskapsvurderingId").asString()
            assertNotEquals(førsteId, nyId)

            assertEquals("saksbehandlerhandling", saksbehandlerhandling.path("@event_name").asString())
            assertEquals(FØDSELSNUMMER, saksbehandlerhandling.path("fødselsnummer").asString())
            assertEquals("2018-02-01", saksbehandlerhandling.path("skjæringstidspunkt").asString())
            assertEquals("Z999999", saksbehandlerhandling.path("saksbehandlerIdent").asString())
            assertEquals("NY_AUTOMATISK_MEDLEMSKAPSVURDERING", saksbehandlerhandling.path("handlingType").asString())
            assertEquals(nyId, saksbehandlerhandling.path("handlingId").asString())
            assertFalse(saksbehandlerhandling.path("kreverTotrinnsvurdering").asBoolean())

            val getRespons = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=$nyId")
            assertEquals(HttpStatusCode.OK, getRespons.status)
            assertTrue(objectMapper.readTree(getRespons.bodyAsText())["krav"].single()["medlemskapOk"].asBoolean())

            // Prøvingen er avsluttet, så en ny runde kan startes igjen
            assertEquals(HttpStatusCode.OK, client.vurderPåNytt(pseudoId).status)
        }

    @Test
    fun `ny automatisk vurdering uten eksisterende vurdering gir 404`() =
        testApplication {
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)
            application { settOppTestapp(pseudoIdProvider) }

            assertEquals(HttpStatusCode.NotFound, client.vurderPåNytt(pseudoId).status)
            outboxJobb.kjørEnRunde()
            assertEquals(0, rapid.inspektør.size)
        }

    private companion object {
        const val FØDSELSNUMMER = "12029240045"
        const val ORGNUMMER = "987654321"

        @Language("JSON")
        fun medlemskapsvurderingBehov(id: UUID) =
            """
        {
          "@event_name": "behov",
          "@id": "$id",
          "@behov": ["Medlemskapsvurdering"],
          "fødselsnummer": "$FØDSELSNUMMER",
          "Medlemskapsvurdering" : {
              "skjæringstidspunkt": "2018-02-01",
              "arbeidssituasjon": "Arbeidstaker"
          }
        }
        """

        fun arbeidsforhold(
            ansattSiden: String,
            ansattTil: String,
        ) = """{ "orgnummer": "$ORGNUMMER", "type": "ORDINÆRT", "ansattSiden": "$ansattSiden", "ansattTil": "$ansattTil" }"""

        @Language("JSON")
        fun arbeidsforholdløsningTilOpprinneligBehov(
            behovId: UUID,
            arbeidsforhold: String,
        ) = """
        {
          "@event_name": "behov",
          "@id": "${UUID.randomUUID()}",
          "@behov": ["ArbeidsforholdV2"],
          "@final": true,
          "fødselsnummer": "$FØDSELSNUMMER",
          "skjæringstidspunkt": "2018-02-01",
          "opprinneligBehov": {
            "@event_name": "behov",
            "@id": "$behovId",
            "@behov": ["Medlemskapsvurdering"],
            "fødselsnummer": "$FØDSELSNUMMER"
          },
          "@løsning": {
            "ArbeidsforholdV2": [$arbeidsforhold]
          }
        }
        """
    }
}
