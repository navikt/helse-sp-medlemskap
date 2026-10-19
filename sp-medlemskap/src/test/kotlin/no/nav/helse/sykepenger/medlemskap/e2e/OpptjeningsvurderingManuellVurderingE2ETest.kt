package no.nav.helse.sykepenger.medlemskap.e2e

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import no.nav.helse.sykepenger.medlemskap.infra.kafka.MedlemskapsvurderingResultatRiver
import no.nav.helse.sykepenger.medlemskap.infra.kafka.MedlemskapsvurderingRiver
import no.nav.helse.sykepenger.medlemskap.infra.kafka.OutboxPubliseringsjobb
import no.nav.helse.sykepenger.medlemskap.infra.rest.GetVilkårsvurderingerForPersonBehandler
import no.nav.helse.sykepenger.medlemskap.infra.rest.PostManuellVilkårsvurderingBehandler
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
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

internal class MedlemskapsvurderingManuellVurderingE2ETest : DatabaseTest() {
    /** Sett til `true` for å dumpe innholdet i databasetabellene mellom hvert steg, se [dump]. */
    private val dump = true

    private fun dump(steg: String) {
        if (!dump) return
        Database.dump("medlemskapsproving", "medlemskapsvurdering", "vilkarsvurdering", "medlemskapsvurdering_vilkarsvurdering", overskrift = steg)
    }

    private val transaksjon = Database.transaksjonProvider
    private val rapid =
        TestRapid().apply {
            MedlemskapsvurderingRiver(this, transaksjon)
            GrunnlagForAutomatiskArbeidstakerMedlemskapsvurderingRiver(this, transaksjon)
            MedlemskapsvurderingResultatRiver(
                this,
                transaksjon,
                object : ISpleisClient {
                    override fun hentMedlemskapsvurderinger(fødselsnummer: String): List<SpleisMedlemskapsvurdering> = emptyList()
                },
            )
        }

    private val outboxJobb = OutboxPubliseringsjobb(rapid, transaksjon, LeaderElection { true })

    private val saksbehandler = Saksbehandler(NavIdent("Z999999"), SaksbehandlerOid("oid"), "Test Testesen")
    private val identitetsnummer = Identitetsnummer(FØDSELSNUMMER)
    private val objectMapper = jacksonObjectMapper()

    private fun Application.settOppTestapp(personPseudoIdProvider: InMemoryPersonPseudoIdProvider) {
        configureContentNegotiation()
        configureResources()

        val principal =
            SaksbehandlerPrincipal(
                saksbehandler,
                setOf(Tilgang.Les, Tilgang.Skriv),
                AccessToken("token"),
            )
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
            post(PostManuellVilkårsvurderingBehandler(), restAdapter)
        }
    }

    @Test
    fun `vurderer en ikke-oppfylt automatisk vurdering manuelt til oppfylt, og resultatriveren følger etter`() =
        testApplication {
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)
            application { settOppTestapp(pseudoIdProvider) }

            dump("0")

            // Steg 1: automatisk vurdering av en arbeidstaker med for kort medlemskap (27 dager, krever 28)
            val behovId = UUID.randomUUID()
            rapid.sendTestMessage(medlemskapsvurderingBehov(behovId, "Arbeidstaker"), FØDSELSNUMMER)

            dump("Mens vi venter på ARBEIDSFORHOLD-BEHOVET :")

            rapid.sendTestMessage(
                arbeidsforholdløsning(behovId, arbeidsforhold(ansattSiden = "2018-01-05", ansattTil = "2018-01-31")),
                FØDSELSNUMMER,
            )
            assertEquals(2, rapid.inspektør.size)

            dump("1")

            val automatiskId =
                rapid.inspektør
                    .message(1)
                    .path("@løsning")
                    .path("Medlemskapsvurdering")
                    .path("id")
                    .asString()

            // Steg 2: REST-API-et er enig med automatikken — ikke rett til sykepenger
            val getRespons = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=$automatiskId")
            assertEquals(HttpStatusCode.OK, getRespons.status)
            val getResponsBody = getRespons.bodyAsText()
            val getJson = objectMapper.readTree(getResponsBody)
            assertFalse(getJson["krav"].single()["medlemskapOk"].asBoolean()) {
                "Automatikken skulle kommet til at medlemskapsvilkåret ikke er oppfylt: $getResponsBody"
            }

            // Steg 3: MedlemskapsvurderingResultat-riveren sier ok=false for den automatiske vurderingen
            rapid.sendTestMessage(medlemskapsvurderingResultatBehov(UUID.fromString(automatiskId)), FØDSELSNUMMER)
            assertEquals(3, rapid.inspektør.size)
            assertFalse(
                rapid.inspektør
                    .message(2)
                    .path("@løsning")
                    .path("MedlemskapsvurderingResultat")
                    .path("ok")
                    .asBoolean(),
            )

            // Steg 4: saksbehandler vurderer manuelt — medlemskap er OK likevel, pga. likestilt ytelse
            @Language("JSON")
            val manuellVurderingRequest = """
            {
              "skjæringstidspunkt": "2018-02-01",
              "vilkårskode": "MEDLEMSKAP_ARBEID_MINST_4_UKER",
              "utfall": "OPPFYLT",
              "fritekstbegrunnelse": "Mangelfulle opplysninger i registeret, har likevel tilstrekkelig medlemskap",
              "journalpostId": ["journalpost-1", "journalpost-2"]
            }
            """
            val postRespons =
                client.post("/api/personer/$pseudoId/vilkarsvurderinger/manuell") {
                    contentType(ContentType.Application.Json)
                    setBody(manuellVurderingRequest)
                }
            assertEquals(HttpStatusCode.OK, postRespons.status)
            val nyId = objectMapper.readTree(postRespons.bodyAsText())["medlemskapsvurderingId"].asString()
            assertNotEquals(automatiskId, nyId) { "Den manuelle vurderingen skal lage en ny medlemskapsvurdering, ikke skrive over den gamle" }

            outboxJobb.kjørEnRundeHvisLeader()

            dump("etter 4")

            // Den nye vurderingen skal vise helheten: den automatiske 4-ukers-vurderingen er med, den manuelle vurderingen er avgjørende
            val getEtterManuellVurdering = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=$nyId")
            assertEquals(HttpStatusCode.OK, getEtterManuellVurdering.status)
            val nyMedlemskapsvurdering = objectMapper.readTree(getEtterManuellVurdering.bodyAsText())["krav"].single()
            assertTrue(nyMedlemskapsvurdering["medlemskapOk"].asBoolean())
            assertEquals("MEDLEMSKAP_ARBEID_MINST_4_UKER", nyMedlemskapsvurdering["avgjørendeVilkårskode"].asString())
            assertEquals(
                listOf("MEDLEMSKAP_ARBEID_MINST_4_UKER"),
                nyMedlemskapsvurdering["vurderinger"].toList().map { it["vilkårskode"].asString() },
            )
            assertEquals("OPPFYLT", nyMedlemskapsvurdering["vurderinger"][0]["utfall"].asString())
            assertEquals(
                listOf("journalpost-1", "journalpost-2"),
                nyMedlemskapsvurdering["vurderinger"]
                    .toList()
                    .single()["kilde"]["journalpostId"]
                    .toList()
                    .map { it.asString() },
            )

            // Den manuelle vurderingen skal ha publisert et event til utregningsappen om den nye medlemskapsvurderingen, i tillegg til et event som forteller om saksbehandlerendringen
            assertEquals(5, rapid.inspektør.size)
            val manuelleHendelser = (3..4).map { rapid.inspektør.message(it) }
            assertEquals(
                setOf("endret_medlemskapsvurdering", "saksbehandlerhandling"),
                manuelleHendelser.map { it.path("@event_name").asString() }.toSet(),
            )
            val manuellVurderingEvent =
                manuelleHendelser.single { it.path("@event_name").asString() == "endret_medlemskapsvurdering" }
            assertEquals(nyId, manuellVurderingEvent.path("medlemskapsvurderingId").asString())
            assertEquals(FØDSELSNUMMER, manuellVurderingEvent.path("fødselsnummer").asString())
            assertEquals("2018-02-01", manuellVurderingEvent.path("skjæringstidspunkt").asString())
            assertTrue(manuellVurderingEvent.path("manuellVurdering").asBoolean())

            // Steg 5: kjør MedlemskapsvurderingResultat-riveren på nytt, nå med den nye id-en — ok=true
            rapid.sendTestMessage(medlemskapsvurderingResultatBehov(UUID.fromString(nyId)), FØDSELSNUMMER)
            assertEquals(6, rapid.inspektør.size)
            assertTrue(
                rapid.inspektør
                    .message(5)
                    .path("@løsning")
                    .path("MedlemskapsvurderingResultat")
                    .path("ok")
                    .asBoolean(),
            )
        }

    @Test
    fun `publiserer saksbehandlerhandling før endret medlemskapsvurdering`() =
        testApplication {
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)
            application { settOppTestapp(pseudoIdProvider) }

            @Language("JSON")
            val manuellVurderingRequest = """
            {
              "skjæringstidspunkt": "2018-02-01",
              "vilkårskode": "MEDLEMSKAP_ARBEID_MINST_4_UKER",
              "utfall": "OPPFYLT",
              "fritekstbegrunnelse": "Manuell vurdering for test av eventrekkefølge",
              "journalpostId": ["journalpost-1"]
            }
            """
            val postRespons =
                client.post("/api/personer/$pseudoId/vilkarsvurderinger/manuell") {
                    contentType(ContentType.Application.Json)
                    setBody(manuellVurderingRequest)
                }
            assertEquals(HttpStatusCode.OK, postRespons.status)

            outboxJobb.kjørEnRundeHvisLeader()

            assertEquals(
                listOf("saksbehandlerhandling", "endret_medlemskapsvurdering"),
                (0 until rapid.inspektør.size).map {
                    rapid.inspektør
                        .message(it)
                        .path("@event_name")
                        .asString()
                },
            )
        }

    private companion object {
        const val FØDSELSNUMMER = "12029240045"
        const val ORGNUMMER = "987654321"

        @Language("JSON")
        fun medlemskapsvurderingBehov(
            id: UUID,
            arbeidssituasjon: String,
        ) = """
        {
          "@event_name": "behov",
          "@id": "$id",
          "@behov": ["Medlemskapsvurdering"],
          "fødselsnummer": "$FØDSELSNUMMER",
          "Medlemskapsvurdering" : {
              "skjæringstidspunkt": "2018-02-01",
              "arbeidssituasjon": "$arbeidssituasjon"
          }
        }
        """

        fun arbeidsforhold(
            orgnummer: String = ORGNUMMER,
            type: String = "ORDINÆRT",
            ansattSiden: String,
            ansattTil: String? = null,
        ) = """
            {
              "orgnummer": "$orgnummer",
              "type": "$type",
              "ansattSiden": "$ansattSiden",
              "ansattTil": ${ansattTil?.let { "\"$it\"" } ?: "null"}
            }
            """.trimIndent()

        @Language("JSON")
        fun arbeidsforholdløsning(
            behovId: UUID,
            vararg arbeidsforhold: String,
            erFinal: Boolean = true,
        ) = """
        {
          "@event_name": "behov",
          "@id": "${UUID.randomUUID()}",
          "@behov": ["ArbeidsforholdV2"],
          "@final": $erFinal,
          "fødselsnummer": "$FØDSELSNUMMER",
          "skjæringstidspunkt": "2018-02-01",
          "opprinneligBehov": {
            "@event_name": "behov",
            "@id": "$behovId",
            "@behov": ["Medlemskapsvurdering"],
            "fødselsnummer": "$FØDSELSNUMMER",
            "skjæringstidspunkt": "2018-02-01",
            "arbeidssituasjon": "Arbeidstaker"
          },
          "@løsning": {
            "ArbeidsforholdV2": [${arbeidsforhold.joinToString()}]
          }
        }
        """

        @Language("JSON")
        fun medlemskapsvurderingResultatBehov(medlemskapsvurderingId: UUID) =
            """
        {
          "@event_name": "behov",
          "@id": "${UUID.randomUUID()}",
          "fødselsnummer": "$FØDSELSNUMMER",
          "@behov": ["MedlemskapsvurderingResultat"],
          "MedlemskapsvurderingResultat": {
            "medlemskapsvurderingId": "$medlemskapsvurderingId"
          }
        }
        """
    }
}
