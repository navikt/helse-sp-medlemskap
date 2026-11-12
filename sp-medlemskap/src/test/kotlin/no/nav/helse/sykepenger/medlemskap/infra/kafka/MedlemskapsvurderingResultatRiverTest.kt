package no.nav.helse.sykepenger.medlemskap.infra.kafka

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.helse.februar
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Vurderingskilde
import no.nav.helse.sykepenger.medlemskap.infra.db.Database
import no.nav.helse.sykepenger.medlemskap.infra.db.DatabaseTest
import no.nav.helse.sykepenger.medlemskap.infra.db.OPPSLAGSPERIODE
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class MedlemskapsvurderingResultatRiverTest : DatabaseTest() {
    private val transaksjon = Database.transaksjonProvider
    private val spleisvurderinger = mutableListOf<SpleisMedlemskapsvurdering>()
    private var antallSpleiskall = 0
    private val rapid =
        TestRapid().apply {
            MedlemskapsvurderingResultatRiver(
                this,
                transaksjon,
                object : ISpleisClient {
                    override fun hentMedlemskapsvurderinger(fødselsnummer: String): List<SpleisMedlemskapsvurdering> {
                        antallSpleiskall++
                        return spleisvurderinger.toList()
                    }
                },
            )
        }

    @ParameterizedTest
    @CsvSource(
        "Ja, JA, true",
        "Nei, NEI, false",
        "Uavklart, UAVKLART, true",
        "UavklartMedBrukerspørsmål, UAVKLART_MED_BRUKERSPORSMAAL, true",
    )
    fun `løser med svar og ok for lagret vurdering`(
        status: Medlemskapsstatus,
        forventetSvar: String,
        forventetOk: Boolean,
    ) {
        val vurdering = Medlemskapsvurdering.overførtFraSpleis(MedlemskapsvurderingId.ny(), FØDSELSNUMMER, 1.februar, status, Instant.now())
        transaksjon.transaksjon { tx -> tx.medlemskapsvurderinger.lagre(vurdering) }

        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(vurdering.id))

        val løsning = rapid.inspektør.message(0).resultat()
        assertEquals(forventetSvar, løsning.path("svar").asString())
        assertEquals(forventetOk, løsning.path("ok").asBoolean())
        assertEquals(0, antallSpleiskall)
    }

    @Test
    fun `henter vurdering fra spleis når den ikke er lagret her`() {
        val id = MedlemskapsvurderingId.ny()
        spleisvurderinger.add(SpleisMedlemskapsvurdering.Spleis(id, 1.februar, Instant.now(), Medlemskapsstatus.Nei))

        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(id))

        val løsning = rapid.inspektør.message(0).resultat()
        assertEquals("NEI", løsning.path("svar").asString())
        assertEquals(false, løsning.path("ok").asBoolean())
    }

    @Test
    fun `vurdering fra infotrygd regnes som medlem`() {
        val id = MedlemskapsvurderingId.ny()
        spleisvurderinger.add(SpleisMedlemskapsvurdering.Infotrygd(id, 1.februar, Instant.now()))

        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(id))

        val løsning = rapid.inspektør.message(0).resultat()
        assertEquals("JA", løsning.path("svar").asString())
        assertEquals(true, løsning.path("ok").asBoolean())
    }

    @Test
    fun `løser ikke behovet når vurderingen ikke finnes`() {
        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(UUID.randomUUID()))

        assertEquals(0, rapid.inspektør.size)
        assertEquals(1, antallSpleiskall)
    }

    @Test
    fun `løser ikke behovet når spleis feiler`() {
        val rapid =
            TestRapid().apply {
                MedlemskapsvurderingResultatRiver(
                    this,
                    transaksjon,
                    object : ISpleisClient {
                        override fun hentMedlemskapsvurderinger(fødselsnummer: String): List<SpleisMedlemskapsvurdering> = error("Spleis er nede")
                    },
                )
            }

        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(UUID.randomUUID()))

        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `logger samsvar på info når spleis har samme status`() {
        val vurdering = automatiskVurdering(Medlemskapsstatus.Ja)
        spleisvurderinger.add(SpleisMedlemskapsvurdering.Spleis(vurdering.id, 1.februar, Instant.now(), Medlemskapsstatus.Ja))

        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(vurdering.id))

        assertEquals(
            "JA",
            rapid.inspektør
                .message(0)
                .resultat()
                .path("svar")
                .asString(),
        )
        assertEquals(1, antallSpleiskall)
        val logg = teamLogs("Medlemskapsvurdering samsvarer med Spleis")
        assertEquals(Level.INFO, logg.level)
        assertTrue(logg.formattedMessage.contains("kilde: \"OPPSLAG\""))
    }

    @Test
    fun `logger avvik på warn og svarer med egen vurdering når forhåndsvurderingen er mer positiv`() {
        val vurdering = automatiskVurdering(Medlemskapsstatus.Ja, forhåndsvurdertSøknadId = UUID.randomUUID())
        spleisvurderinger.add(
            SpleisMedlemskapsvurdering.Spleis(vurdering.id, 1.februar, Instant.now(), Medlemskapsstatus.UavklartMedBrukerspørsmål),
        )

        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(vurdering.id))

        assertEquals(
            "JA",
            rapid.inspektør
                .message(0)
                .resultat()
                .path("svar")
                .asString(),
        )
        val logg = teamLogs("Medlemskapsvurdering avviker fra Spleis")
        assertEquals(Level.WARN, logg.level)
        assertTrue(logg.formattedMessage.contains("kilde: \"FORHANDSVURDERING\""))
        assertTrue(logg.formattedMessage.contains("statusSpMedlemskap: \"JA\""))
        assertTrue(logg.formattedMessage.contains("statusSpleis: \"UAVKLART_MED_BRUKERSPORSMAAL\""))
        assertTrue(logg.formattedMessage.contains("retning: \"SP_MEDLEMSKAP_MER_POSITIV\""))
        assertEquals(FØDSELSNUMMER, logg.mdcPropertyMap["identitetsnummer"])
    }

    @Test
    fun `logger avvik som SP_MEDLEMSKAP_MER_NEGATIV når egen vurdering er strengere enn spleis`() {
        val vurdering = automatiskVurdering(Medlemskapsstatus.Nei)
        spleisvurderinger.add(SpleisMedlemskapsvurdering.Infotrygd(vurdering.id, 1.februar, Instant.now()))

        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(vurdering.id))

        assertEquals(
            "NEI",
            rapid.inspektør
                .message(0)
                .resultat()
                .path("svar")
                .asString(),
        )
        assertTrue(teamLogs("Medlemskapsvurdering avviker fra Spleis").formattedMessage.contains("retning: \"SP_MEDLEMSKAP_MER_NEGATIV\""))
    }

    @Test
    fun `logger warn når vurderingen ikke finnes i spleis`() {
        val vurdering = automatiskVurdering(Medlemskapsstatus.Ja)

        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(vurdering.id))

        assertEquals(
            "JA",
            rapid.inspektør
                .message(0)
                .resultat()
                .path("svar")
                .asString(),
        )
        assertEquals(Level.WARN, teamLogs("Fant ikke medlemskapsvurderingen i Spleis for sammenligning").level)
    }

    @Test
    fun `svarer med egen vurdering selv om sammenligningen mot spleis feiler`() {
        val vurdering = automatiskVurdering(Medlemskapsstatus.Ja)
        val rapid =
            TestRapid().apply {
                MedlemskapsvurderingResultatRiver(
                    this,
                    transaksjon,
                    object : ISpleisClient {
                        override fun hentMedlemskapsvurderinger(fødselsnummer: String): List<SpleisMedlemskapsvurdering> = error("Spleis er nede")
                    },
                )
            }

        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(vurdering.id))

        assertEquals(
            "JA",
            rapid.inspektør
                .message(0)
                .resultat()
                .path("svar")
                .asString(),
        )
        assertEquals(Level.WARN, teamLogs("Klarte ikke sammenligne medlemskapsvurdering med Spleis").level)
    }

    private fun automatiskVurdering(
        status: Medlemskapsstatus,
        forhåndsvurdertSøknadId: UUID? = null,
    ) = Medlemskapsvurdering(
        id = MedlemskapsvurderingId.ny(),
        fødselsnummer = FØDSELSNUMMER,
        skjæringstidspunkt = 1.februar,
        status = status,
        vurdertTidspunkt = Instant.now(),
        kilde =
            Vurderingskilde.Automatisk(
                medlemskapsprøvingId = MedlemskapsprøvingId.ny(),
                oppslagsperiode = OPPSLAGSPERIODE,
                versjonAvKildekode = "test",
                forhåndsvurdertSøknadId = forhåndsvurdertSøknadId,
            ),
    ).also { vurdering -> transaksjon.transaksjon { tx -> tx.medlemskapsvurderinger.lagre(vurdering) } }

    private val loggContext = LoggerFactory.getILoggerFactory() as LoggerContext
    private val loggfanger =
        object : ListAppender<ILoggingEvent>() {
            // Tar vare på MDC mens den er satt. Logback leser den ellers først når vi spør etter den.
            override fun append(event: ILoggingEvent) {
                event.prepareForDeferredProcessing()
                super.append(event)
            }
        }.apply {
            context = loggContext
        }

    @BeforeEach
    fun fangLogger() {
        loggfanger.start()
        loggContext.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(loggfanger)
    }

    @AfterEach
    fun slippLogger() {
        loggContext.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(loggfanger)
        loggfanger.stop()
    }

    private fun teamLogs(melding: String) =
        loggfanger.list.single { event ->
            event.markerList?.any { it.name == "NOT_NAV_LOGS" } == true && event.formattedMessage.startsWith(melding)
        }

    private fun JsonNode.resultat() = path("@løsning").path("MedlemskapsvurderingResultat")
}
