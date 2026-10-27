package no.nav.helse.sykepenger.medlemskap.e2e

import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.e2e.E2ETestapp.Companion.løsMedlemskapsbehov
import no.nav.helse.sykepenger.medlemskap.e2e.E2ETestapp.Companion.medlemskapsvurderingBehov
import no.nav.helse.sykepenger.medlemskap.infra.db.Database
import no.nav.helse.sykepenger.medlemskap.infra.db.DatabaseTest
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Sett til `true` for å pretty printe alle Kafka-meldinger (inn og ut) i testene til konsollen. */
private const val PRETTY_PRINT_KAFKA_MELDINGER = true

internal class MedlemskapE2ETest : DatabaseTest() {
    private val apper = mutableListOf<E2ETestapp>()

    private fun app(spleisvurderinger: List<SpleisMedlemskapsvurdering> = emptyList()) = E2ETestapp(spleisvurderinger, PRETTY_PRINT_KAFKA_MELDINGER).also { apper += it }

    @AfterEach
    fun skrivUtGjenværendeMeldinger() {
        apper.forEach { it.skrivUtNyeUtgåendeMeldinger() }
    }

    @ParameterizedTest
    @CsvSource(
        "JA, true",
        "NEI, false",
        "UAVKLART, true",
        "UAVKLART_MED_BRUKERSPORSMAAL, true",
    )
    fun `svaret fra medlemskapstjenesten gir Spleis svar og ok`(
        svar: String,
        forventetOk: Boolean,
    ) {
        val app = app()

        val vurderingId = app.vurderAutomatisk(svar)

        assertEquals(1, Database.antallRader("medlemskapsproving"))
        assertEquals(1, Database.antallRader("medlemskapsvurdering"))
        val resultat = app.resultat(vurderingId)
        assertEquals(svar, resultat.path("svar").asString())
        assertEquals(forventetOk, resultat.path("ok").asBoolean())
    }

    @Test
    fun `behovet om medlemskap har det sparkel-medlemskap trenger`() {
        val app = app()

        app.rapid.sendTestMessage(medlemskapsvurderingBehov(UUID.randomUUID()), FØDSELSNUMMER)

        val behov = app.meldinger.single()
        assertEquals(FØDSELSNUMMER, app.rapid.inspektør.key(0))
        assertEquals(FØDSELSNUMMER, behov.path("fødselsnummer").asString())
        assertEquals(true, behov.hasNonNull("vedtaksperiodeId"))
        assertEquals("2018-02-01", behov.path("Medlemskap").path("medlemskapPeriodeFom").asString())
        assertEquals("2018-02-28", behov.path("Medlemskap").path("medlemskapPeriodeTom").asString())
        assertEquals(0, Database.antallRader("medlemskapsvurdering"))
    }

    @Test
    fun `nytt behov for samme skjæringstidspunkt gjenbruker vurderingen`() {
        val app = app()
        val første = app.vurderAutomatisk("JA")

        val behovId = UUID.randomUUID()
        app.rapid.sendTestMessage(medlemskapsvurderingBehov(behovId), FØDSELSNUMMER)

        val løsning = app.meldinger.last()
        assertEquals(behovId.toString(), løsning.path("@forårsaket_av").path("id").asString())
        assertEquals(
            første,
            løsning
                .path("@løsning")
                .path("Medlemskapsvurdering")
                .path("id")
                .asString(),
        )
        assertEquals(1, Database.antallRader("medlemskapsproving"))
    }

    @Test
    fun `nytt skjæringstidspunkt gir ny vurdering`() {
        val app = app()

        val første = app.vurderAutomatisk("JA", skjæringstidspunkt = "2018-02-01")
        val andre = app.vurderAutomatisk("NEI", skjæringstidspunkt = "2018-02-15")

        assertNotEquals(første, andre)
        assertEquals(false, app.resultat(andre).path("ok").asBoolean())
        assertEquals(true, app.resultat(første).path("ok").asBoolean())
    }

    @Test
    fun `duplisert løsning fra sparkel gir bare én vurdering og ett svar`() {
        val app = app()
        app.rapid.sendTestMessage(medlemskapsvurderingBehov(UUID.randomUUID()), FØDSELSNUMMER)
        val løst = løsMedlemskapsbehov(app.meldinger.single(), "JA")

        app.rapid.sendTestMessage(løst, FØDSELSNUMMER)
        app.rapid.sendTestMessage(løst, FØDSELSNUMMER)

        assertEquals(2, app.rapid.inspektør.size)
        assertEquals(1, Database.antallRader("medlemskapsvurdering"))
    }

    @Test
    fun `resultat for vurdering gjort i Spleis hentes derfra`() {
        val id = MedlemskapsvurderingId.ny()
        val app =
            app(
                listOf(SpleisMedlemskapsvurdering.Spleis(id, LocalDate.of(2018, 1, 1), Instant.now(), Medlemskapsstatus.UavklartMedBrukerspørsmål)),
            )

        val resultat = app.resultat(id.toString())

        assertEquals("UAVKLART_MED_BRUKERSPORSMAAL", resultat.path("svar").asString())
        assertEquals(true, resultat.path("ok").asBoolean())
    }
}
