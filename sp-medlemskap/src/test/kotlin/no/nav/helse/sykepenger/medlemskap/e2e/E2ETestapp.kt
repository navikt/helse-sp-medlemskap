package no.nav.helse.sykepenger.medlemskap.e2e

import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.auth.authentication
import io.ktor.server.routing.routing
import no.nav.helse.sykepenger.medlemskap.application.PersonAvstemmingService
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.infra.db.Database
import no.nav.helse.sykepenger.medlemskap.infra.kafka.GrunnlagForAutomatiskMedlemskapsvurderingRiver
import no.nav.helse.sykepenger.medlemskap.infra.kafka.GrunnlagForNyMedlemskapsvurderingRiver
import no.nav.helse.sykepenger.medlemskap.infra.kafka.MedlemskapsvurderingResultatRiver
import no.nav.helse.sykepenger.medlemskap.infra.kafka.MedlemskapsvurderingRiver
import no.nav.helse.sykepenger.medlemskap.infra.kafka.OutboxPubliseringsjobb
import no.nav.helse.sykepenger.medlemskap.infra.rest.GetVilkårsvurderingerForPersonBehandler
import no.nav.helse.sykepenger.medlemskap.infra.rest.PostManuellVilkårsvurderingBehandler
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

internal const val FØDSELSNUMMER = "12029240045"

/** Rivere, outbox og REST-API koblet sammen mot en ekte database, slik som i appen. */
internal class E2ETestapp(
    spleisvurderinger: List<SpleisMedlemskapsvurdering> = emptyList(),
) {
    private val transaksjon = Database.transaksjonProvider
    private val spleisClient =
        object : ISpleisClient {
            override fun hentMedlemskapsvurderinger(fødselsnummer: String) = spleisvurderinger
        }

    val rapid =
        TestRapid().apply {
            MedlemskapsvurderingRiver(this, transaksjon)
            GrunnlagForAutomatiskMedlemskapsvurderingRiver(this, transaksjon)
            GrunnlagForNyMedlemskapsvurderingRiver(this, transaksjon)
            MedlemskapsvurderingResultatRiver(this, transaksjon, spleisClient)
        }

    val outboxJobb = OutboxPubliseringsjobb(rapid, transaksjon, LeaderElection { true })

    val meldinger get() = (0 until rapid.inspektør.size).map { rapid.inspektør.message(it) }

    fun Application.settOpp(personPseudoIdProvider: InMemoryPersonPseudoIdProvider) {
        configureContentNegotiation()
        configureResources()
        val principal =
            SaksbehandlerPrincipal(
                Saksbehandler(NavIdent(SAKSBEHANDLER), SaksbehandlerOid("oid"), "Test Testesen"),
                setOf(Tilgang.Les, Tilgang.Skriv),
                AccessToken("token"),
            )
        intercept(ApplicationCallPipeline.Plugins) {
            call.authentication.principal(principal)
        }
        val restAdapter =
            RestAdapter<Transaksjonskontekst>(
                personPseudoIdProvider = personPseudoIdProvider,
                populasjonstilgangskontrollProvider = AltErLov,
                transaksjonProvider = transaksjon,
            )
        routing {
            get(GetVilkårsvurderingerForPersonBehandler(PersonAvstemmingService(transaksjon, spleisClient)), restAdapter)
            post(PostManuellVilkårsvurderingBehandler(), restAdapter)
            post(PostNyAutomatiskVilkårsvurderingBehandler(publiser = { key, melding -> rapid.publish(key, melding) }), restAdapter)
        }
    }

    /** Spleis ber om en vurdering, og sparkel-medlemskap svarer. Gir id-en Spleis får tilbake. */
    fun vurderAutomatisk(
        svar: String,
        skjæringstidspunkt: String = "2018-02-01",
    ): String {
        val behovId = UUID.randomUUID()
        rapid.sendTestMessage(medlemskapsvurderingBehov(behovId, skjæringstidspunkt), FØDSELSNUMMER)
        val medlemskapsbehov = meldinger.last()
        check(medlemskapsbehov.path("@behov").toList().map { it.asString() } == listOf("Medlemskap"))
        rapid.sendTestMessage(løsMedlemskapsbehov(medlemskapsbehov, svar), FØDSELSNUMMER)
        val løsning = meldinger.last()
        check(løsning.path("@id").asString() == behovId.toString())
        return løsning
            .path("@løsning")
            .path("Medlemskapsvurdering")
            .path("id")
            .asString()
    }

    /** Spleis ber om resultatet av en vurdering. */
    fun resultat(medlemskapsvurderingId: String): JsonNode {
        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(medlemskapsvurderingId), FØDSELSNUMMER)
        return meldinger.last().path("@løsning").path("MedlemskapsvurderingResultat")
    }

    companion object {
        const val SAKSBEHANDLER = "Z999999"
        private val objectMapper = jacksonObjectMapper()

        @Language("JSON")
        fun medlemskapsvurderingBehov(
            id: UUID,
            skjæringstidspunkt: String = "2018-02-01",
        ) = """
            {
              "@event_name": "behov",
              "@id": "$id",
              "@opprettet": "2018-02-01T12:00:00",
              "@behov": ["Medlemskapsvurdering"],
              "fødselsnummer": "$FØDSELSNUMMER",
              "vedtaksperiodeId": "${UUID.randomUUID()}",
              "Medlemskapsvurdering": {
                "skjæringstidspunkt": "$skjæringstidspunkt",
                "medlemskapPeriodeFom": "$skjæringstidspunkt",
                "medlemskapPeriodeTom": "2018-02-28"
              }
            }
            """

        @Language("JSON")
        fun medlemskapsvurderingResultatBehov(medlemskapsvurderingId: String) =
            """
            {
              "@event_name": "behov",
              "@id": "${UUID.randomUUID()}",
              "@opprettet": "2018-02-01T12:00:00",
              "@behov": ["MedlemskapsvurderingResultat"],
              "fødselsnummer": "$FØDSELSNUMMER",
              "MedlemskapsvurderingResultat": {
                "medlemskapsvurderingId": "$medlemskapsvurderingId"
              }
            }
            """

        /** Slik sparkel-medlemskap og behovsakkumulatoren løser et `Medlemskap`-behov */
        fun løsMedlemskapsbehov(
            behov: JsonNode,
            svar: String,
        ): String {
            val løst = behov.deepCopy() as ObjectNode
            løst
                .putObject("@løsning")
                .putObject("Medlemskap")
                .putObject("resultat")
                .put("svar", svar)
            løst.put("@final", true)
            return objectMapper.writeValueAsString(løst)
        }
    }
}

private object AltErLov : PopulasjonstilgangskontrollProvider {
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
}
