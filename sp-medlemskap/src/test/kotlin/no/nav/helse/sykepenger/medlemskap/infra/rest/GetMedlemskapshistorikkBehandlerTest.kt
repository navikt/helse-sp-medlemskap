package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangSomMangler
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
import no.nav.helse.sykepenger.medlemskap.application.InMemoryTransaksjonProvider
import no.nav.helse.sykepenger.medlemskap.application.PersonAvstemmingService
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class GetMedlemskapshistorikkBehandlerTest {
    private val saksbehandler = Saksbehandler(NavIdent("Z999999"), SaksbehandlerOid("oid"), "Test Testesen")
    private val identitetsnummer = Identitetsnummer("12345678901")
    private val skjæringstidspunkt = LocalDate.of(2024, 2, 1)

    private class FakeTilgangskontroll(
        private val resultat: TilgangskontrollResultat = TilgangskontrollResultat.Ok,
    ) : PopulasjonstilgangskontrollProvider {
        override fun kontrollerKomplettTilgang(
            accessToken: String,
            fødselsnummer: String,
        ) = resultat

        override fun kontrollerKjerneTilgang(
            accessToken: String,
            fødselsnummer: String,
        ) = resultat

        override fun kontrollerKjerneTilgangForAnsatt(
            ansattId: String,
            fødselsnummer: String,
        ) = resultat
    }

    private class FakeSpleisClient(
        private val vurderinger: List<SpleisMedlemskapsvurdering> = emptyList(),
        private val svikt: RuntimeException? = null,
    ) : ISpleisClient {
        override fun hentMedlemskapsvurderinger(fødselsnummer: String): List<SpleisMedlemskapsvurdering> {
            svikt?.let { throw it }
            return vurderinger
        }
    }

    private val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
    private val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

    private fun Application.settOppTestapp(
        tilganger: Set<Tilgang> = setOf(Tilgang.Les),
        transaksjonProvider: InMemoryTransaksjonProvider = InMemoryTransaksjonProvider(),
        tilgangskontroll: PopulasjonstilgangskontrollProvider = FakeTilgangskontroll(),
        spleisClient: ISpleisClient = FakeSpleisClient(),
    ) {
        configureContentNegotiation()
        configureResources()
        intercept(ApplicationCallPipeline.Plugins) {
            call.authentication.principal(SaksbehandlerPrincipal(saksbehandler, tilganger, AccessToken("token")))
        }
        val restAdapter =
            RestAdapter<Transaksjonskontekst>(
                personPseudoIdProvider = pseudoIdProvider,
                populasjonstilgangskontrollProvider = tilgangskontroll,
                transaksjonProvider = transaksjonProvider,
            )
        routing {
            get(GetMedlemskapshistorikkBehandler(PersonAvstemmingService(transaksjonProvider, spleisClient)), restAdapter)
        }
    }

    private suspend fun ApplicationTestBuilder.hentHistorikk(
        personId: String = pseudoId.toString(),
        skjæringstidspunkt: LocalDate = this@GetMedlemskapshistorikkBehandlerTest.skjæringstidspunkt,
    ) = client.get("/api/personer/$personId/medlemskapsvurderinger/historikk") {
        parameter("skjæringstidspunkt", skjæringstidspunkt.toString())
    }

    @Test
    fun `kall uten Les-tilgang gir 403`() =
        testApplication {
            application { settOppTestapp(tilganger = emptySet()) }

            assertEquals(HttpStatusCode.Forbidden, hentHistorikk().status)
        }

    @Test
    fun `manglende populasjonstilgang gir 403`() =
        testApplication {
            application {
                settOppTestapp(tilgangskontroll = FakeTilgangskontroll(TilgangskontrollResultat.ManglerTilgang(TilgangSomMangler.Habilitet)))
            }

            assertEquals(HttpStatusCode.Forbidden, hentHistorikk().status)
        }

    @Test
    fun `ukjent pseudo-id gir 404`() =
        testApplication {
            application { settOppTestapp() }

            assertEquals(HttpStatusCode.NotFound, hentHistorikk(personId = UUID.randomUUID().toString()).status)
        }

    @Test
    fun `ingen vurderinger gir tom historikk`() =
        testApplication {
            application { settOppTestapp() }

            val response = hentHistorikk()

            assertEquals(HttpStatusCode.OK, response.status)
            val json = jacksonObjectMapper().readTree(response.bodyAsText())
            assertEquals("2024-02-01", json["skjæringstidspunkt"].asString())
            assertTrue(json["historikk"].isEmpty)
        }

    @Test
    fun `historikken har personens vurderinger på skjæringstidspunktet, nyeste først`() =
        testApplication {
            val transaksjonProvider = InMemoryTransaksjonProvider()
            val automatisk = spleisvurdering(Medlemskapsstatus.Nei, Instant.parse("2025-04-01T10:03:00Z"))
            val manuell = saksbehandlervurdering(Medlemskapsstatus.Ja, Instant.parse("2025-07-25T12:21:00Z"))
            val annetSkjæringstidspunkt = saksbehandlervurdering(Medlemskapsstatus.Ja, Instant.now(), skjæringstidspunkt = LocalDate.of(2024, 3, 1))
            val annenPerson = saksbehandlervurdering(Medlemskapsstatus.Ja, Instant.now(), fødselsnummer = "98765432109")
            listOf(automatisk, manuell, annetSkjæringstidspunkt, annenPerson).forEach(transaksjonProvider.medlemskapsvurderinger::lagre)

            application { settOppTestapp(transaksjonProvider = transaksjonProvider) }

            val response = hentHistorikk()
            assertEquals(HttpStatusCode.OK, response.status)

            val historikk = jacksonObjectMapper().readTree(response.bodyAsText())["historikk"]
            assertEquals(listOf(manuell.id.toString(), automatisk.id.toString()), historikk.toList().map { it["medlemskapsvurdering"]["id"].asString() })
            assertEquals("2025-07-25T12:21:00Z", historikk.first()["vurdertTidspunkt"].asString())
            assertEquals("SAKSBEHANDLER", historikk.first()["medlemskapsvurdering"]["kilde"]["kildetype"].asString())
            assertEquals("JA", historikk.first()["medlemskapsvurdering"]["status"].asString())
            assertEquals("NEI", historikk.last()["medlemskapsvurdering"]["status"].asString())
        }

    @Test
    fun `vurderinger fra spleis blir med i historikken for personer som ikke er migrert`() =
        testApplication {
            val fraSpleis =
                SpleisMedlemskapsvurdering.Infotrygd(
                    medlemskapsvurderingId = MedlemskapsvurderingId(UUID.randomUUID()),
                    skjæringstidspunkt = skjæringstidspunkt,
                    opprettet = Instant.parse("2024-02-02T00:00:00Z"),
                )

            application { settOppTestapp(spleisClient = FakeSpleisClient(vurderinger = listOf(fraSpleis))) }

            val historikk = jacksonObjectMapper().readTree(hentHistorikk().bodyAsText())["historikk"]

            val innslag = historikk.single()
            assertEquals(fraSpleis.medlemskapsvurderingId.toString(), innslag["medlemskapsvurdering"]["id"].asString())
            assertEquals("OVERFOERT_FRA_INFOTRYGD", innslag["medlemskapsvurdering"]["kilde"]["kildetype"].asString())
        }

    @Test
    fun `spleis-api svikter gir 503 selv om noe finnes i db, siden historikken da kan være ufullstendig`() =
        testApplication {
            val transaksjonProvider = InMemoryTransaksjonProvider()
            transaksjonProvider.medlemskapsvurderinger.lagre(saksbehandlervurdering(Medlemskapsstatus.Ja, Instant.now()))

            application {
                settOppTestapp(
                    transaksjonProvider = transaksjonProvider,
                    spleisClient = FakeSpleisClient(svikt = SpleisClientException("spleis-api svarte 500")),
                )
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, hentHistorikk().status)
        }

    private fun spleisvurdering(
        status: Medlemskapsstatus,
        vurdertTidspunkt: Instant,
    ) = Medlemskapsvurdering.overførtFraSpleis(MedlemskapsvurderingId.ny(), identitetsnummer.value, skjæringstidspunkt, status, vurdertTidspunkt)

    private fun saksbehandlervurdering(
        status: Medlemskapsstatus,
        vurdertTidspunkt: Instant,
        fødselsnummer: String = identitetsnummer.value,
        skjæringstidspunkt: LocalDate = this.skjæringstidspunkt,
    ) = Medlemskapsvurdering
        .avSaksbehandler(fødselsnummer, skjæringstidspunkt, status, "B111222", "Begrunnelse")
        .copy(vurdertTidspunkt = vurdertTidspunkt)
}
