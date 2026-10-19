package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangSomMangler
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.auth.authentication
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.medlemskap.application.InMemoryMigreringsloggRepository
import no.nav.helse.sykepenger.medlemskap.application.InMemoryTransaksjonProvider
import no.nav.helse.sykepenger.medlemskap.application.PersonAvstemmingService
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsgrunnlag
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisClientException
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.infra.spleis.tilMedlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.*
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureContentNegotiation
import no.nav.helse.sykepenger.medlemskap.rammeverk.plugins.configureResources
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.RestAdapter
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.get
import no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures.InMemoryPersonPseudoIdProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.*

class GetVilkårsvurderingerForPersonBehandlerTest {
    private val saksbehandler = Saksbehandler(NavIdent("Z999999"), SaksbehandlerOid("oid"), "Test Testesen")
    private val identitetsnummer = Identitetsnummer("12345678901")

    private class FakeTilgangskontroll(
        private val resultat: TilgangskontrollResultat = TilgangskontrollResultat.Ok,
    ) : PopulasjonstilgangskontrollProvider {
        var antallKall = 0

        override fun kontrollerKomplettTilgang(
            accessToken: String,
            fødselsnummer: String,
        ): TilgangskontrollResultat = resultat

        override fun kontrollerKjerneTilgang(
            accessToken: String,
            fødselsnummer: String,
        ): TilgangskontrollResultat {
            antallKall++
            return resultat
        }

        override fun kontrollerKjerneTilgangForAnsatt(
            ansattId: String,
            fødselsnummer: String,
        ): TilgangskontrollResultat = resultat
    }

    private fun principal(tilganger: Set<Tilgang> = setOf(Tilgang.Les)) = SaksbehandlerPrincipal(saksbehandler, tilganger, AccessToken("token"))

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

    private fun Application.settOppTestapp(
        principal: SaksbehandlerPrincipal?,
        transaksjonProvider: InMemoryTransaksjonProvider = InMemoryTransaksjonProvider(),
        tilgangskontroll: PopulasjonstilgangskontrollProvider = FakeTilgangskontroll(),
        personPseudoIdProvider: InMemoryPersonPseudoIdProvider = InMemoryPersonPseudoIdProvider(),
        spleisClient: ISpleisClient = FakeSpleisClient(),
    ) {
        configureContentNegotiation()
        configureResources()
        if (principal != null) {
            intercept(ApplicationCallPipeline.Plugins) {
                call.authentication.principal(principal)
            }
        }
        val restAdapter =
            RestAdapter<Transaksjonskontekst>(
                personPseudoIdProvider = personPseudoIdProvider,
                populasjonstilgangskontrollProvider = tilgangskontroll,
                transaksjonProvider = transaksjonProvider,
            )
        routing {
            get(GetVilkårsvurderingerForPersonBehandler(PersonAvstemmingService(transaksjonProvider, spleisClient)), restAdapter)
        }
    }

    @Test
    fun `kall uten Les-tilgang gir 403`() =
        testApplication {
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

            application {
                settOppTestapp(principal(tilganger = emptySet()), personPseudoIdProvider = pseudoIdProvider)
            }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${UUID.randomUUID()}")

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `ukjent pseudo-id gir 404, ikke 500`() =
        testApplication {
            application { settOppTestapp(principal()) }

            val response = client.get("/api/personer/${UUID.randomUUID()}/vilkarsvurderinger?medlemskapsvurderingId=${UUID.randomUUID()}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `ugyldig (ikke-UUID) person-id gir 404, ikke 500`() =
        testApplication {
            application { settOppTestapp(principal()) }

            val response = client.get("/api/personer/dette-er-ikke-en-uuid/vilkarsvurderinger?medlemskapsvurderingId=${UUID.randomUUID()}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `manglende populasjonstilgang gir 403`() =
        testApplication {
            val fake = FakeTilgangskontroll(TilgangskontrollResultat.ManglerTilgang(TilgangSomMangler.Habilitet))
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

            application {
                settOppTestapp(principal(), tilgangskontroll = fake, personPseudoIdProvider = pseudoIdProvider)
            }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${UUID.randomUUID()}")

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(1, fake.antallKall)
        }

    @Test
    fun `ukjent medlemskapsvurderingId gir 404`() =
        testApplication {
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

            application {
                settOppTestapp(principal(), personPseudoIdProvider = pseudoIdProvider)
            }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${UUID.randomUUID()}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `vurdering som tilhoerer en annen person gir 404, lekker ikke andres data`() =
        testApplication {
            val enAnnenIdentitetsnummer = Identitetsnummer("98765432109")
            val transaksjonProvider = InMemoryTransaksjonProvider()
            val andresVurdering =
                Medlemskapsvurdering.automatisk(
                    medlemskapsprøvingId = MedlemskapsprøvingId.ny(),
                    fødselsnummer = enAnnenIdentitetsnummer.value,
                    skjæringstidspunkt = LocalDate.of(2024, 1, 1),
                    grunnlag = Medlemskapsgrunnlag.SelvstendigNæringsdrivende,
                )
            transaksjonProvider.medlemskapsvurderinger.lagre(andresVurdering)

            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

            application {
                settOppTestapp(principal(), transaksjonProvider, personPseudoIdProvider = pseudoIdProvider)
            }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${andresVurdering.id.value}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    /**
     * Formen på json-en er selve kontrakten mot Speil, og den produseres av Jackson — ikke av
     * kotlinx-annotasjonene, som kun styrer openapi-spec-en. Testen går derfor helt ut på wire, og
     * ville ikke fanget en manglende `@JsonTypeInfo` om den bare sammenlignet kotlin-objekter.
     */
    @Test
    fun `responsen har diskriminator paa kilde og grunnlag`() =
        testApplication {
            val transaksjonProvider = InMemoryTransaksjonProvider()
            val vurdering =
                Medlemskapsvurdering.automatisk(
                    medlemskapsprøvingId = MedlemskapsprøvingId.ny(),
                    fødselsnummer = identitetsnummer.value,
                    skjæringstidspunkt = LocalDate.of(2024, 2, 1),
                    grunnlag = Medlemskapsgrunnlag.SelvstendigNæringsdrivende,
                )
            transaksjonProvider.medlemskapsvurderinger.lagre(vurdering)

            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

            application {
                settOppTestapp(principal(), transaksjonProvider, personPseudoIdProvider = pseudoIdProvider)
            }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${vurdering.id.value}")
            assertEquals(HttpStatusCode.OK, response.status)

            val json = jacksonObjectMapper().readTree(response.bodyAsText())

            assertEquals("2024-02-01", json["skjæringstidspunkt"].asString())

            val krav = json["krav"].single()
            assertEquals("MEDLEMSKAP", krav["kravkode"].asString())
            assertEquals(true, krav["medlemskapOk"].asBoolean())
            assertEquals("VURDERT_I_SP_medlemskap", krav["kravkilde"].asString())
            assertEquals("MEDLEMSKAP_ARBEID_MINST_4_UKER", krav["avgjørendeVilkårskode"].asString())

            val vilkårsvurdering = krav["vurderinger"].single()
            assertEquals("MEDLEMSKAP_ARBEID_MINST_4_UKER", vilkårsvurdering["vilkårskode"].asString())
            assertEquals("OPPFYLT", vilkårsvurdering["utfall"].asString())

            val kilde = vilkårsvurdering["kilde"]
            assertEquals("AUTOMATISK", kilde["kildetype"].asString()) { "Uten diskriminator kan Speil ikke se hvilken kilde den fikk: $kilde" }
            assertEquals("SELVSTENDIG_NAERINGSDRIVENDE", kilde["grunnlag"]["grunnlagstype"].asString())

            // Diskriminatoren er både en deklarert property og en Jackson-annotasjon. Skrives den to
            // ganger, er json-en i praksis ødelagt for strengt validerende konsumenter.
            assertEquals(1, Regex("\"kildetype\"").findAll(response.bodyAsText()).count())
        }

    @Test
    fun `infotrygdkrav sendes uten enkeltvurderinger og uten avgjørende vilkår`() =
        testApplication {
            val transaksjonProvider = InMemoryTransaksjonProvider()
            val vurdering =
                Medlemskapsvurdering.fraInfotrygd(
                    fødselsnummer = identitetsnummer.value,
                    skjæringstidspunkt = LocalDate.of(2024, 2, 1),
                    erOk = true,
                    vurdertTidspunkt = Instant.now(),
                )
            transaksjonProvider.medlemskapsvurderinger.lagre(vurdering)

            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

            application {
                settOppTestapp(principal(), transaksjonProvider, personPseudoIdProvider = pseudoIdProvider)
            }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${vurdering.id.value}")
            assertEquals(HttpStatusCode.OK, response.status)

            val krav = jacksonObjectMapper().readTree(response.bodyAsText())["krav"].single()

            assertEquals("OVERFOERT_FRA_INFOTRYGD", krav["kravkilde"].asString())
            assertEquals(true, krav["medlemskapOk"].asBoolean())
            assertFalse(krav.has("vurderinger")) { "Infotrygd-kravet skal ikke ha enkeltvurderinger: $krav" }
            assertFalse(krav.has("avgjørendeVilkårskode")) { "Vi kjenner ikke det avgjørende vilkåret: $krav" }
        }

    /**
     * Vurderingen finnes ikke i vår database — typisk fordi den ble gjort automatisk i spleis før
     * dette systemet begynte å lagre vurderinger selv. Da skal vi falle tilbake til spleis-api.
     */
    @Test
    fun `finner ikke vurdering i db, men finner den hos spleis som arbeidstaker`() =
        testApplication {
            val medlemskapsvurderingId = UUID.randomUUID()
            val spleisVurdering =
                SpleisMedlemskapsvurdering.SpleisArbeidstaker(
                    medlemskapsvurderingId = MedlemskapsvurderingId(medlemskapsvurderingId),
                    opprettet = Instant.now(),
                    skjæringstidspunkt = LocalDate.of(2024, 3, 1),
                    oppfylt = true,
                    antallDager = 30,
                    medlemskapsperiode = null,
                    arbeidsforhold =
                        listOf(
                            SpleisMedlemskapsvurdering.SpleisArbeidstaker.Arbeidsforhold(
                                organisasjonsnummer = "123456789",
                                ansettelsesperioder =
                                    listOf(
                                        SpleisMedlemskapsvurdering.SpleisArbeidstaker.Ansettelsesperiode(
                                            fom = LocalDate.of(2024, 1, 1),
                                            tom = null,
                                        ),
                                    ),
                            ),
                        ),
                )

            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

            application {
                settOppTestapp(
                    principal(),
                    personPseudoIdProvider = pseudoIdProvider,
                    spleisClient = FakeSpleisClient(vurderinger = listOf(spleisVurdering)),
                )
            }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=$medlemskapsvurderingId")
            assertEquals(HttpStatusCode.OK, response.status)

            val krav = jacksonObjectMapper().readTree(response.bodyAsText())["krav"].single()
            assertEquals("OVERFORT_FRA_SPLEIS", krav["kravkilde"].asString())
            assertEquals(true, krav["medlemskapOk"].asBoolean())

            val kilde = krav["vurderinger"].single()["kilde"]
            assertEquals("OVERFOERT_FRA_SPLEIS", kilde["kildetype"].asString())
        }

    @Test
    fun `finner ikke vurdering i db, men finner den hos spleis som selvstendig`() =
        testApplication {
            val medlemskapsvurderingId = UUID.randomUUID()
            val spleisVurdering =
                SpleisMedlemskapsvurdering.SpleisSelvstendig(
                    medlemskapsvurderingId = MedlemskapsvurderingId(medlemskapsvurderingId),
                    opprettet = Instant.now(),
                    skjæringstidspunkt = LocalDate.of(2024, 3, 1),
                )

            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

            application {
                settOppTestapp(
                    principal(),
                    personPseudoIdProvider = pseudoIdProvider,
                    spleisClient = FakeSpleisClient(vurderinger = listOf(spleisVurdering)),
                )
            }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=$medlemskapsvurderingId")
            assertEquals(HttpStatusCode.OK, response.status)

            val krav = jacksonObjectMapper().readTree(response.bodyAsText())["krav"].single()
            assertEquals(true, krav["medlemskapOk"].asBoolean())
        }

    @Test
    fun `vurderingene fra spleis lagres i db og registreres i migreringsloggen`() =
        testApplication {
            val etterspurt = spleisSelvstendig()
            val enAnnen = spleisSelvstendig()
            val transaksjonProvider = InMemoryTransaksjonProvider()
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

            application {
                settOppTestapp(
                    principal(),
                    transaksjonProvider,
                    personPseudoIdProvider = pseudoIdProvider,
                    spleisClient = FakeSpleisClient(vurderinger = listOf(etterspurt, enAnnen)),
                )
            }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${etterspurt.medlemskapsvurderingId.value}")
            assertEquals(HttpStatusCode.OK, response.status)

            assertEquals(
                setOf(etterspurt.medlemskapsvurderingId, enAnnen.medlemskapsvurderingId),
                transaksjonProvider.medlemskapsvurderinger.alleVurderinger
                    .map { it.id }
                    .toSet(),
            ) { "Alle personens vurderinger skal overføres, ikke bare den som ble etterspurt" }
            assertEquals(
                setOf(identitetsnummer.value),
                transaksjonProvider.medlemskapsvurderinger.alleVurderinger
                    .map { it.fødselsnummer }
                    .toSet(),
            )
            assertEquals(
                InMemoryMigreringsloggRepository.Nedlasting(identitetsnummer.value, antallVurderinger = 2, antallHoppetOver = 0),
                transaksjonProvider.migreringslogg.alleNedlastinger.single(),
            )
        }

    @Test
    fun `spleis kalles bare første gang, deretter leses vurderingen fra db`() =
        testApplication {
            val spleisVurdering = spleisSelvstendig()
            val spleisClient = FakeSpleisClient(vurderinger = listOf(spleisVurdering))
            val transaksjonProvider = InMemoryTransaksjonProvider()
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

            application {
                settOppTestapp(principal(), transaksjonProvider, personPseudoIdProvider = pseudoIdProvider, spleisClient = spleisClient)
            }

            repeat(3) {
                val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${spleisVurdering.medlemskapsvurderingId.value}")
                assertEquals(HttpStatusCode.OK, response.status)
            }

            assertEquals(1, spleisClient.antallKall)
            assertEquals(1, transaksjonProvider.medlemskapsvurderinger.antallLagringer)
            assertEquals(1, transaksjonProvider.migreringslogg.alleNedlastinger.size)
        }

    @Test
    fun `vurderinger som allerede finnes i db lagres ikke paa nytt`() =
        testApplication {
            val spleisVurdering = spleisSelvstendig()
            val transaksjonProvider = InMemoryTransaksjonProvider()
            transaksjonProvider.medlemskapsvurderinger.lagre(spleisVurdering.tilMedlemskapsvurdering(identitetsnummer.value))
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

            application {
                settOppTestapp(
                    principal(),
                    transaksjonProvider,
                    personPseudoIdProvider = pseudoIdProvider,
                    spleisClient = FakeSpleisClient(vurderinger = listOf(spleisVurdering)),
                )
            }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${spleisVurdering.medlemskapsvurderingId.value}")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, transaksjonProvider.medlemskapsvurderinger.antallLagringer)
            assertEquals(
                InMemoryMigreringsloggRepository.Nedlasting(identitetsnummer.value, antallVurderinger = 1, antallHoppetOver = 1),
                transaksjonProvider.migreringslogg.alleNedlastinger.single(),
            )
        }

    @Test
    fun `avstemmer ikke når saksbehandler mangler populasjonstilgang`() =
        testApplication {
            val spleisClient = FakeSpleisClient(vurderinger = listOf(spleisSelvstendig()))
            val transaksjonProvider = InMemoryTransaksjonProvider()
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

            application {
                settOppTestapp(
                    principal(),
                    transaksjonProvider,
                    tilgangskontroll = FakeTilgangskontroll(TilgangskontrollResultat.ManglerTilgang(TilgangSomMangler.Habilitet)),
                    personPseudoIdProvider = pseudoIdProvider,
                    spleisClient = spleisClient,
                )
            }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${UUID.randomUUID()}")

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(0, spleisClient.antallKall)
            assertEquals(0, transaksjonProvider.medlemskapsvurderinger.antallLagringer)
            assertEquals(0, transaksjonProvider.migreringslogg.alleNedlastinger.size)
        }

    @Test
    fun `finner ikke vurdering i db, men finner den hos spleis som overfoert fra infotrygd`() =
        testApplication {
            val medlemskapsvurderingId = UUID.randomUUID()
            val spleisVurdering =
                SpleisMedlemskapsvurdering.InfotrygdArbeidstaker(
                    medlemskapsvurderingId = MedlemskapsvurderingId(medlemskapsvurderingId),
                    skjæringstidspunkt = LocalDate.of(2024, 3, 1),
                    opprettet = Instant.now(),
                )

            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

            application {
                settOppTestapp(
                    principal(),
                    personPseudoIdProvider = pseudoIdProvider,
                    spleisClient = FakeSpleisClient(vurderinger = listOf(spleisVurdering)),
                )
            }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=$medlemskapsvurderingId")
            assertEquals(HttpStatusCode.OK, response.status)

            val krav = jacksonObjectMapper().readTree(response.bodyAsText())["krav"].single()
            assertEquals("OVERFOERT_FRA_INFOTRYGD", krav["kravkilde"].asString())
            assertEquals(true, krav["medlemskapOk"].asBoolean())
        }

    @Test
    fun `finnes verken i db eller hos spleis gir 404`() =
        testApplication {
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

            application {
                settOppTestapp(principal(), personPseudoIdProvider = pseudoIdProvider, spleisClient = FakeSpleisClient())
            }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${UUID.randomUUID()}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `spleis-api svikter gir 503, ikke 500`() =
        testApplication {
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

            application {
                settOppTestapp(
                    principal(),
                    personPseudoIdProvider = pseudoIdProvider,
                    spleisClient = FakeSpleisClient(svikt = SpleisClientException("spleis-api svarte 500")),
                )
            }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${UUID.randomUUID()}")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

    @Test
    fun `spleis-api svikter, men vurderingen finnes allerede i db gir 200`() =
        testApplication {
            val transaksjonProvider = InMemoryTransaksjonProvider()
            val vurdering =
                Medlemskapsvurdering.automatisk(
                    medlemskapsprøvingId = MedlemskapsprøvingId.ny(),
                    fødselsnummer = identitetsnummer.value,
                    skjæringstidspunkt = LocalDate.of(2024, 2, 1),
                    grunnlag = Medlemskapsgrunnlag.SelvstendigNæringsdrivende,
                )
            transaksjonProvider.medlemskapsvurderinger.lagre(vurdering)
            val pseudoIdProvider = InMemoryPersonPseudoIdProvider()
            val pseudoId = pseudoIdProvider.nyPersonPseudoId(identitetsnummer)

            application {
                settOppTestapp(
                    principal(),
                    transaksjonProvider,
                    personPseudoIdProvider = pseudoIdProvider,
                    spleisClient = FakeSpleisClient(svikt = SpleisClientException("spleis-api svarte 500")),
                )
            }

            val response = client.get("/api/personer/$pseudoId/vilkarsvurderinger?medlemskapsvurderingId=${vurdering.id.value}")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(0, transaksjonProvider.migreringslogg.alleNedlastinger.size) {
                "Feilet avstemming skal ikke registreres, ellers prøver vi aldri igjen"
            }
        }

    private fun spleisSelvstendig() =
        SpleisMedlemskapsvurdering.SpleisSelvstendig(
            medlemskapsvurderingId = MedlemskapsvurderingId(UUID.randomUUID()),
            opprettet = Instant.now(),
            skjæringstidspunkt = LocalDate.of(2024, 3, 1),
        )
}
