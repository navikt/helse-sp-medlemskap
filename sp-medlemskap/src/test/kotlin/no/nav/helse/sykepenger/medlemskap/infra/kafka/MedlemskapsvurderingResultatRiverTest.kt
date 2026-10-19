package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.helse.februar
import no.nav.helse.sykepenger.medlemskap.application.InMemoryTransaksjonProvider
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

internal class MedlemskapsvurderingResultatRiverTest {
    private val transaksjon = InMemoryTransaksjonProvider()
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
        transaksjon.medlemskapsvurderinger.lagre(vurdering)

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

    private fun JsonNode.resultat() = path("@løsning").path("MedlemskapsvurderingResultat")
}
