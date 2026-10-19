package no.nav.helse.sykepenger.medlemskap.e2e

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.infra.db.Database
import no.nav.helse.sykepenger.medlemskap.infra.db.DatabaseTest
import no.nav.helse.sykepenger.medlemskap.infra.kafka.GrunnlagForAutomatiskArbeidstakerMedlemskapsvurderingRiver
import no.nav.helse.sykepenger.medlemskap.infra.kafka.MedlemskapsvurderingResultatRiver
import no.nav.helse.sykepenger.medlemskap.infra.kafka.MedlemskapsvurderingRiver
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.TransaksjonProvider
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

internal class MedlemskapE2ETest : DatabaseTest() {
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

    // === Arbeidstaker-flyt ===

    @Test
    fun `arbeidstaker med nok medlemskap får ok=true`() {
        val behovId = UUID.randomUUID()

        // Steg 1: spleis ber om medlemskapsvurdering
        rapid.sendTestMessage(medlemskapsvurderingBehov(behovId, "Arbeidstaker"), FØDSELSNUMMER)
        assertEquals(1, rapid.inspektør.size)

        val arbeidsforholdBehov = rapid.inspektør.message(0)
        assertEquals("behov", arbeidsforholdBehov.path("@event_name").asString())
        assertEquals(listOf("ArbeidsforholdV2"), arbeidsforholdBehov.path("@behov").toList().map { it.asString() })

        // Prøvingen er lagret og venter på grunnlag; ingen vurdering ennå
        assertEquals(1, Database.antallRader("medlemskapsproving"))
        assertEquals(0, Database.antallRader("medlemskapsvurdering"))

        // Steg 2: Aareg svarer med arbeidsforhold som gir nok medlemskap (28+ dager)
        rapid.sendTestMessage(
            arbeidsforholdløsning(
                behovId = behovId,
                arbeidsforhold(ansattSiden = "2018-01-01", ansattTil = "2018-01-31"),
            ),
            FØDSELSNUMMER,
        )
        assertEquals(2, rapid.inspektør.size)

        // Vurderingen og den fullførte prøvingen ble skrevet i samme transaksjon
        assertEquals(1, Database.antallRader("medlemskapsproving"))
        assertEquals(1, Database.antallRader("medlemskapsvurdering"))

        val medlemskapsvurderingLøsning = rapid.inspektør.message(1)
        assertEquals(behovId.toString(), medlemskapsvurderingLøsning.path("@id").asString())
        val vurderingId =
            UUID.fromString(
                medlemskapsvurderingLøsning
                    .path("@løsning")
                    .path("Medlemskapsvurdering")
                    .path("id")
                    .asString(),
            )

        // Steg 3: spleis ber om resultatet av vurderingen
        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(vurderingId), FØDSELSNUMMER)
        assertEquals(3, rapid.inspektør.size)

        val resultat = rapid.inspektør.message(2)
        assertTrue(
            resultat
                .path("@løsning")
                .path("MedlemskapsvurderingResultat")
                .path("ok")
                .asBoolean(),
        )
    }

    @Test
    fun `arbeidstaker med for kort medlemskap får ok=false`() {
        val behovId = UUID.randomUUID()

        rapid.sendTestMessage(medlemskapsvurderingBehov(behovId, "Arbeidstaker"), FØDSELSNUMMER)

        // For kort: kun 27 dager (2018-01-05 til 2018-01-31 = 27 dager)
        rapid.sendTestMessage(
            arbeidsforholdløsning(
                behovId = behovId,
                arbeidsforhold(ansattSiden = "2018-01-05", ansattTil = "2018-01-31"),
            ),
            FØDSELSNUMMER,
        )

        val vurderingId =
            UUID.fromString(
                rapid.inspektør
                    .message(1)
                    .path("@løsning")
                    .path("Medlemskapsvurdering")
                    .path("id")
                    .asString(),
            )

        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(vurderingId), FØDSELSNUMMER)

        assertFalse(
            rapid.inspektør
                .message(2)
                .path("@løsning")
                .path("MedlemskapsvurderingResultat")
                .path("ok")
                .asBoolean(),
        )
    }

    @Test
    fun `løpende arbeidsforhold (uten ansattTil) gir ok=true`() {
        val behovId = UUID.randomUUID()

        rapid.sendTestMessage(medlemskapsvurderingBehov(behovId, "Arbeidstaker"), FØDSELSNUMMER)
        rapid.sendTestMessage(
            arbeidsforholdløsning(
                behovId = behovId,
                arbeidsforhold(ansattSiden = "2018-01-01", ansattTil = null),
            ),
            FØDSELSNUMMER,
        )

        val vurderingId =
            UUID.fromString(
                rapid.inspektør
                    .message(1)
                    .path("@løsning")
                    .path("Medlemskapsvurdering")
                    .path("id")
                    .asString(),
            )
        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(vurderingId), FØDSELSNUMMER)

        assertTrue(
            rapid.inspektør
                .message(2)
                .path("@løsning")
                .path("MedlemskapsvurderingResultat")
                .path("ok")
                .asBoolean(),
        )
    }

    @Test
    fun `partisjonsnøkkel bevares gjennom hele arbeidstakerflyt`() {
        val behovId = UUID.randomUUID()
        val partisjonsnøkkel = "12029240045"

        rapid.sendTestMessage(medlemskapsvurderingBehov(behovId, "Arbeidstaker"), partisjonsnøkkel)
        assertEquals(partisjonsnøkkel, rapid.inspektør.key(0), "ArbeidsforholdV2-behov skal ha riktig partisjonsnøkkel")

        rapid.sendTestMessage(
            arbeidsforholdløsning(behovId = behovId, arbeidsforhold(ansattSiden = "2018-01-01", ansattTil = "2018-01-31")),
            partisjonsnøkkel,
        )
        assertEquals(partisjonsnøkkel, rapid.inspektør.key(1), "Medlemskapsvurdering-løsning skal ha riktig partisjonsnøkkel")
    }

    @Test
    fun `duplikat medlemskapsvurdering-behov sender ikke nytt ArbeidsforholdV2-behov`() {
        val behovId = UUID.randomUUID()

        rapid.sendTestMessage(medlemskapsvurderingBehov(behovId, "Arbeidstaker"), FØDSELSNUMMER)
        assertEquals(1, rapid.inspektør.size)

        // Fullfør vurderingen
        rapid.sendTestMessage(
            arbeidsforholdløsning(behovId = behovId, arbeidsforhold(ansattSiden = "2018-01-01", ansattTil = "2018-01-31")),
            FØDSELSNUMMER,
        )
        assertEquals(2, rapid.inspektør.size)

        // Spleis sender behovet på nytt (replay)
        rapid.sendTestMessage(medlemskapsvurderingBehov(UUID.randomUUID(), "Arbeidstaker"), FØDSELSNUMMER)
        assertEquals(3, rapid.inspektør.size)

        // Skal svare direkte med eksisterende vurdering, ikke sende nytt ArbeidsforholdV2-behov
        val tredjeUtgang = rapid.inspektør.message(2)
        assertTrue(tredjeUtgang.hasNonNull("@løsning")) { "Skal svare med løsning, ikke nytt behov" }
        assertTrue(tredjeUtgang.path("@løsning").hasNonNull("Medlemskapsvurdering")) { "Løsningen skal inneholde Medlemskapsvurdering" }
    }

    @Test
    fun `duplikat arbeidsforholdløsning gir ikke dobbelt svar`() {
        val behovId = UUID.randomUUID()
        rapid.sendTestMessage(medlemskapsvurderingBehov(behovId, "Arbeidstaker"), FØDSELSNUMMER)

        val løsning = arbeidsforholdløsning(behovId = behovId, arbeidsforhold(ansattSiden = "2018-01-01", ansattTil = "2018-01-31"))
        rapid.sendTestMessage(løsning, FØDSELSNUMMER)
        assertEquals(2, rapid.inspektør.size)

        rapid.sendTestMessage(løsning, FØDSELSNUMMER)
        assertEquals(2, rapid.inspektør.size) // Ingen ny melding
        assertEquals(1, Database.antallRader("medlemskapsvurdering"))
    }

    // === Selvstendig næringsdrivende-flyt ===

    @Test
    fun `selvstendig næringsdrivende løses uten ArbeidsforholdV2-behov og får ok=true`() {
        val behovId = UUID.randomUUID()

        // Steg 1: spleis ber om medlemskapsvurdering
        rapid.sendTestMessage(medlemskapsvurderingBehov(behovId, "SelvstendigNæringsdrivende"), FØDSELSNUMMER)
        assertEquals(1, rapid.inspektør.size)

        val løsning = rapid.inspektør.message(0)
        assertFalse(løsning.path("@behov").any { it.asString() == "ArbeidsforholdV2" }) {
            "SelvstendigNæringsdrivende skal ikke trenge ArbeidsforholdV2"
        }
        val vurderingId =
            UUID.fromString(
                løsning
                    .path("@løsning")
                    .path("Medlemskapsvurdering")
                    .path("id")
                    .asString(),
            )

        // Steg 2: spleis ber om resultatet
        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(vurderingId), FØDSELSNUMMER)
        assertEquals(2, rapid.inspektør.size)

        val resultat = rapid.inspektør.message(1)
        assertTrue(
            resultat
                .path("@løsning")
                .path("MedlemskapsvurderingResultat")
                .path("ok")
                .asBoolean(),
        )
    }

    // Vurderingen ligger i databasen, så et nytt behov besvares direkte — også etter at prosessen
    // har startet på nytt (nye rivers, ingen tilstand i minnet, samme database)
    @Test
    fun `lagret vurdering gjenbrukes av en ny instans`() {
        rapid.sendTestMessage(medlemskapsvurderingBehov(UUID.randomUUID(), "SelvstendigNæringsdrivende"), FØDSELSNUMMER)
        val vurderingId =
            rapid.inspektør
                .message(0)
                .path("@løsning")
                .path("Medlemskapsvurdering")
                .path("id")
                .asString()

        val nyRapid =
            TestRapid().apply {
                MedlemskapsvurderingRiver(this, transaksjon)
            }
        nyRapid.sendTestMessage(medlemskapsvurderingBehov(UUID.randomUUID(), "SelvstendigNæringsdrivende"), FØDSELSNUMMER)

        assertEquals(1, nyRapid.inspektør.size)
        assertEquals(
            vurderingId,
            nyRapid.inspektør
                .message(0)
                .path("@løsning")
                .path("Medlemskapsvurdering")
                .path("id")
                .asString(),
        ) { "Eksisterende vurdering skal gjenbrukes" }
        assertEquals(1, Database.antallRader("medlemskapsvurdering"))
    }

    // Feiler behandlingen av en melding etter at arbeidet er gjort, skal ingenting være lagret
    @Test
    fun `feil i behandlingen av en melding lagrer ingenting`() {
        val feilendeRapid =
            TestRapid().apply {
                MedlemskapsvurderingRiver(this, feilerEtterArbeidet)
            }

        assertThrows<RuntimeException> {
            feilendeRapid.sendTestMessage(medlemskapsvurderingBehov(UUID.randomUUID(), "SelvstendigNæringsdrivende"), FØDSELSNUMMER)
        }

        assertEquals(0, Database.antallRader("medlemskapsproving"))
        assertEquals(0, Database.antallRader("medlemskapsvurdering"))
    }

    /** Gjør alt arbeidet i en ekte transaksjon, men krasjer før commit. */
    private val feilerEtterArbeidet =
        object : TransaksjonProvider<Transaksjonskontekst> {
            override fun <T> transaksjon(block: (Transaksjonskontekst) -> T): T =
                transaksjon.transaksjon { kontekst ->
                    block(kontekst)
                    throw RuntimeException("krasjer før commit")
                }
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
