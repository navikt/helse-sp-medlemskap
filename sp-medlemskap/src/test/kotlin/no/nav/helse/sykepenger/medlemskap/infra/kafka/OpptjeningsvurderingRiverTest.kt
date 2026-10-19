package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.helse.februar
import no.nav.helse.januar
import no.nav.helse.sykepenger.medlemskap.application.InMemoryTransaksjonProvider
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidsforhold
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidsforhold.Arbeidsforholdtype.ORDINÆRT
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidssituasjon
import no.nav.helse.sykepenger.medlemskap.domain.Grunnlagsbehov
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsgrunnlag
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsprøving
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER
import no.nav.helse.til
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

internal class MedlemskapsvurderingRiverTest {
    private val transaksjon = InMemoryTransaksjonProvider()
    private val vurderinger = transaksjon.medlemskapsvurderinger
    private val prøvinger = transaksjon.medlemskapsprøvinger
    private val rapid =
        TestRapid().apply {
            MedlemskapsvurderingRiver(this, transaksjon)
        }

    // For arbeidstakere må vi hente arbeidsforhold før vi kan vurdere, så riveren
    // sender ut et nytt behov i stedet for en løsning
    @Test
    fun `arbeidstaker gir behov om arbeidsforhold`() {
        rapid.sendTestMessage(medlemskapsvurderingBehov(arbeidssituasjon = "Arbeidstaker"), "123")

        assertEquals(1, rapid.inspektør.size)
        val utgående = rapid.inspektør.message(0)
        val partisjonsnøkkelForUtgåendeMelding = rapid.inspektør.key(0)
        assertEquals("123", partisjonsnøkkelForUtgåendeMelding)
        assertEquals("behov", utgående.path("@event_name").asString())
        assertEquals(listOf("ArbeidsforholdV2"), utgående.path("@behov").toList().map { it.asString() })
        assertEquals(FØDSELSNUMMER, utgående.path("fødselsnummer").asString())
        assertEquals("2018-02-01", utgående.path("skjæringstidspunkt").asString())
        assertTrue(utgående.hasNonNull("opprinneligBehov"))
    }

    // Prøvingen er prosessen som venter på grunnlaget; ingen vurdering finnes ennå
    @Test
    fun `arbeidstaker starter en prøving uten å produsere en vurdering`() {
        rapid.sendTestMessage(medlemskapsvurderingBehov(arbeidssituasjon = "Arbeidstaker"))

        val prøving = prøvinger.allePrøvinger.single()
        assertFalse(prøving.erAvsluttet)
        assertEquals(Grunnlagsbehov.Arbeidsforhold, prøving.uteståendeBehov)
        assertEquals(0, vurderinger.antallLagringer)
    }

    // Selvstendig næringsdrivende kan vurderes med en gang, og løsningen legges på
    // det innkommende behovet
    @Test
    fun `selvstendig næringsdrivende løses umiddelbart`() {
        rapid.sendTestMessage(medlemskapsvurderingBehov(arbeidssituasjon = "SelvstendigNæringsdrivende"))

        assertEquals(1, rapid.inspektør.size)
        val løsning = rapid.inspektør.message(0)
        val medlemskapsvurderingId =
            MedlemskapsvurderingId(
                UUID.fromString(
                    løsning
                        .path("@løsning")
                        .path("Medlemskapsvurdering")
                        .path("id")
                        .asString(),
                ),
            )

        val vurdering = vurderinger.finn(medlemskapsvurderingId) as Medlemskapsvurdering.VurdertISpVilkårsprøving
        assertEquals(MEDLEMSKAP_ARBEID_MINST_4_UKER, vurdering.avgjørendeVilkårskode)
        assertTrue(prøvinger.allePrøvinger.single().erAvsluttet)
    }

    // Har vi allerede en vurdering svarer vi med den eksisterende id-en
    // i stedet for å be om arbeidsforhold på nytt
    @Test
    fun `eksisterende vurdering svares ut direkte`() {
        val eksisterende = fullførtPrøving()

        rapid.sendTestMessage(medlemskapsvurderingBehov(arbeidssituasjon = "Arbeidstaker"))

        assertEquals(1, rapid.inspektør.size)
        val løsning = rapid.inspektør.message(0)
        assertEquals(
            eksisterende.toString(),
            løsning
                .path("@løsning")
                .path("Medlemskapsvurdering")
                .path("id")
                .asString(),
        )
    }

    @Test
    fun `opprinneligBehov er json-objekt med identisk innhold som innkommende behov`() {
        val innkommendeBehov = medlemskapsvurderingBehov(arbeidssituasjon = "Arbeidstaker")
        rapid.sendTestMessage(innkommendeBehov)

        val utgående = rapid.inspektør.message(0)
        val opprinneligBehov = utgående.path("opprinneligBehov")

        assertInstanceOf(ObjectNode::class.java, opprinneligBehov) { "opprinneligBehov skal være et JSON-objekt, ikke en string" }

        val objectMapper =
            jacksonObjectMapper()
        val forventet = objectMapper.readTree(innkommendeBehov)
        // Rapids beriker meldingen med metadata-felter — vi sjekker at alle opprinnelige felter er bevart
        forventet.properties().forEach { (key, value) ->
            assertEquals(value, opprinneligBehov.get(key)) { "Feltet '$key' i opprinneligBehov stemmer ikke" }
        }
    }

    // Riveren skal ikke plukke opp behov som allerede har fått en løsning
    @Test
    fun `behov med løsning ignoreres`() {
        @Language("JSON")
        val melding = """
        {
          "@event_name": "behov",
          "@id": "${UUID.randomUUID()}",
          "@behov": ["Medlemskapsvurdering"],
          "fødselsnummer": "$FØDSELSNUMMER",
          "skjæringstidspunkt": "2018-02-01",
          "arbeidssituasjon": "Arbeidstaker",
          "@løsning": { "Medlemskapsvurdering": { "id": "${UUID.randomUUID()}" } }
        }
        """
        rapid.sendTestMessage(melding)

        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `andre behov ignoreres`() {
        @Language("JSON")
        val melding = """
        {
          "@event_name": "behov",
          "@id": "${UUID.randomUUID()}",
          "@behov": ["EtHeltAnnetBehov"],
          "fødselsnummer": "$FØDSELSNUMMER",
          "skjæringstidspunkt": "2018-02-01",
          "arbeidssituasjon": "Arbeidstaker"
        }
        """
        rapid.sendTestMessage(melding)

        assertEquals(0, rapid.inspektør.size)
    }

    // Mangler påkrevde felter skal meldingen ikke behandles
    @Test
    fun `behov uten skjæringstidspunkt ignoreres`() {
        @Language("JSON")
        val melding = """
        {
          "@event_name": "behov",
          "@id": "${UUID.randomUUID()}",
          "@behov": ["Medlemskapsvurdering"],
          "fødselsnummer": "$FØDSELSNUMMER",
          "Medlemskapsvurdering" : {
            "arbeidssituasjon": "Arbeidstaker"
          }
        }
        """
        rapid.sendTestMessage(melding)

        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `behov med ukjent arbeidssituasjon feiler`() {
        assertThrows<IllegalArgumentException> {
            rapid.sendTestMessage(medlemskapsvurderingBehov(arbeidssituasjon = "Ukjent"))
        }

        assertEquals(0, rapid.inspektør.size)
        assertEquals(0, prøvinger.allePrøvinger.size)
    }

    private fun fullførtPrøving(): MedlemskapsvurderingId {
        val prøving = Medlemskapsprøving.start(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker).prøving
        val arbeidsforhold = Arbeidsforhold(orgnummer = ORGNUMMER, ansettelseperiode = 1.januar til 31.januar, type = ORDINÆRT)
        val vurdering = prøving.motta(Medlemskapsgrunnlag.Arbeidstaker(listOf(arbeidsforhold)))
        prøvinger.lagre(prøving)
        vurderinger.lagre(vurdering)
        return vurdering.id
    }

    private companion object {
        const val FØDSELSNUMMER = "12029240045"
        const val ORGNUMMER = "987654321"

        @Language("JSON")
        fun medlemskapsvurderingBehov(arbeidssituasjon: String) =
            """
        {
          "@event_name": "behov",
          "@id": "${UUID.randomUUID()}",
          "@behov": ["Medlemskapsvurdering"],
          "fødselsnummer": "$FØDSELSNUMMER",
          "Medlemskapsvurdering" : {
              "skjæringstidspunkt": "2018-02-01",
              "arbeidssituasjon": "$arbeidssituasjon"
          }
        }
        """
    }
}
