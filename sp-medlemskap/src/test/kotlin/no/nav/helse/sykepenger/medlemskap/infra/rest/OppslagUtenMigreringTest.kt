package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.auth.authentication
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.medlemskap.application.PersonAvstemmingService
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.infra.db.Database
import no.nav.helse.sykepenger.medlemskap.infra.db.DatabaseTest
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisClientException
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering
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
import no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures.InMemoryPersonPseudoIdProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Speil kan lese vurderingene før sp-medlemskap gjør vurderingen selv, uten at personen migreres. */
internal class OppslagUtenMigreringTest : DatabaseTest() {
    private val saksbehandler = Saksbehandler(NavIdent("Z999999"), SaksbehandlerOid("oid"), "Test Testesen")
    private val identitetsnummer = Identitetsnummer("12345678901")
    private val skjæringstidspunkt = LocalDate.of(2024, 2, 1)
    private val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
    private val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

    private object TillatAlt : PopulasjonstilgangskontrollProvider {
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

    private class FakeSpleisClient(
        private val vurderinger: List<SpleisMedlemskapsvurdering> = emptyList(),
        private val svikt: RuntimeException? = null,
    ) : ISpleisClient {
        var antallKall = 0

        override fun hentMedlemskapsvurderinger(fødselsnummer: String): List<SpleisMedlemskapsvurdering> {
            antallKall++
            svikt?.let { throw it }
            return vurderinger
        }
    }

    private fun Application.settOppTestapp(spleisClient: ISpleisClient) {
        configureContentNegotiation()
        configureResources()
        intercept(ApplicationCallPipeline.Plugins) {
            call.authentication.principal(SaksbehandlerPrincipal(saksbehandler, setOf(Tilgang.Les), AccessToken("token")))
        }
        val restAdapter =
            RestAdapter<Transaksjonskontekst>(
                personPseudoIdProvider = pseudoIdProvider,
                populasjonstilgangskontrollProvider = TillatAlt,
                transaksjonProvider = Database.transaksjonProvider,
            )
        val avstemmingService = PersonAvstemmingService(Database.transaksjonProvider, spleisClient)
        routing {
            get(GetVilkårsvurderingerForPersonBehandler(avstemmingService, migrerVedOppslag = false), restAdapter)
            get(GetMedlemskapshistorikkBehandler(avstemmingService, migrerVedOppslag = false), restAdapter)
        }
    }

    private suspend fun ApplicationTestBuilder.hentVurdering(id: MedlemskapsvurderingId) = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${id.value}")

    private suspend fun ApplicationTestBuilder.hentHistorikk() =
        client.get("/api/personer/$pseudoId/medlemskapsvurderinger/historikk") {
            parameter("skjæringstidspunkt", skjæringstidspunkt.toString())
        }

    @Test
    fun `vurdering fra Spleis vises uten at personen migreres`() =
        testApplication {
            val fraSpleis = spleisvurdering()
            application { settOppTestapp(FakeSpleisClient(listOf(fraSpleis))) }

            val response = hentVurdering(fraSpleis.medlemskapsvurderingId)

            assertEquals(HttpStatusCode.OK, response.status)
            val krav = jacksonObjectMapper().readTree(response.bodyAsText())["krav"].single()
            assertEquals(fraSpleis.medlemskapsvurderingId.toString(), krav["id"].asString())
            assertEquals("OVERFOERT_FRA_SPLEIS", krav["kilde"]["kildetype"].asString())
            assertEquals(0, Database.antallRader("medlemskapsvurdering"))
            assertEquals(0, Database.antallRader("migreringslogg"))
        }

    @Test
    fun `lagret vurdering vises uten å spørre Spleis`() =
        testApplication {
            val lagret = saksbehandlervurdering()
            Database.transaksjonProvider.transaksjon { it.medlemskapsvurderinger.lagre(lagret) }
            val spleisClient = FakeSpleisClient()
            application { settOppTestapp(spleisClient) }

            assertEquals(HttpStatusCode.OK, hentVurdering(lagret.id).status)
            assertEquals(0, spleisClient.antallKall)
        }

    @Test
    fun `ukjent vurdering gir 404`() =
        testApplication {
            application { settOppTestapp(FakeSpleisClient(listOf(spleisvurdering()))) }

            assertEquals(HttpStatusCode.NotFound, hentVurdering(MedlemskapsvurderingId(UUID.randomUUID())).status)
        }

    @Test
    fun `Spleis svikter gir 503`() =
        testApplication {
            application { settOppTestapp(FakeSpleisClient(svikt = SpleisClientException("spleis-api svarte 500"))) }

            assertEquals(HttpStatusCode.ServiceUnavailable, hentVurdering(MedlemskapsvurderingId(UUID.randomUUID())).status)
            assertEquals(HttpStatusCode.ServiceUnavailable, hentHistorikk().status)
        }

    @Test
    fun `historikken slår sammen lagrede vurderinger og vurderinger fra Spleis, nyeste først, uten å migrere`() =
        testApplication {
            val fraSpleis = spleisvurdering(opprettet = Instant.parse("2024-02-02T00:00:00Z"))
            val annetSkjæringstidspunkt = spleisvurdering(skjæringstidspunkt = LocalDate.of(2024, 3, 1))
            val manuell = saksbehandlervurdering(vurdertTidspunkt = Instant.parse("2024-03-01T00:00:00Z"))
            Database.transaksjonProvider.transaksjon { it.medlemskapsvurderinger.lagre(manuell) }
            application { settOppTestapp(FakeSpleisClient(listOf(fraSpleis, annetSkjæringstidspunkt))) }

            val response = hentHistorikk()

            assertEquals(HttpStatusCode.OK, response.status)
            val historikk = jacksonObjectMapper().readTree(response.bodyAsText())["historikk"]
            assertEquals(
                listOf(manuell.id.toString(), fraSpleis.medlemskapsvurderingId.toString()),
                historikk.toList().map { it["medlemskapsvurdering"]["id"].asString() },
            )
            assertEquals(1, Database.antallRader("medlemskapsvurdering"))
            assertEquals(0, Database.antallRader("migreringslogg"))
        }

    private fun spleisvurdering(
        skjæringstidspunkt: LocalDate = this.skjæringstidspunkt,
        opprettet: Instant = Instant.parse("2024-02-02T00:00:00Z"),
    ) = SpleisMedlemskapsvurdering.Spleis(
        medlemskapsvurderingId = MedlemskapsvurderingId(UUID.randomUUID()),
        opprettet = opprettet,
        skjæringstidspunkt = skjæringstidspunkt,
        status = Medlemskapsstatus.UavklartMedBrukerspørsmål,
    )

    private fun saksbehandlervurdering(vurdertTidspunkt: Instant = Instant.now()) =
        Medlemskapsvurdering
            .avSaksbehandler(identitetsnummer.value, skjæringstidspunkt, Medlemskapsstatus.Ja, "B111222", "Begrunnelse")
            .copy(vurdertTidspunkt = vurdertTidspunkt)
}
