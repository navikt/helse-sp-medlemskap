package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.helse.februar
import no.nav.helse.januar
import no.nav.helse.somPeriode
import no.nav.helse.sykepenger.medlemskap.application.InMemoryTransaksjonProvider
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Utfall
import no.nav.helse.sykepenger.medlemskap.domain.Vilkårskode
import no.nav.helse.sykepenger.medlemskap.domain.Vilkårsvurdering
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.*

class MedlemskapsvurderingResultatRiverCallthroughTest {
    private val transaksjon = InMemoryTransaksjonProvider()
    private val vurderingIdSpleisArbeidstaker = UUID.randomUUID()
    private val vurderingIdSpleisArbeidstakerIkkeOppfylt = UUID.randomUUID()
    private val vurderingIdSpleisSelvstendig = UUID.randomUUID()
    private val vurderingIdSpleisInfotrygd = UUID.randomUUID()
    private val fellesOpprettet = Instant.now()
    private val ekstraSpleisvurderinger = mutableListOf<SpleisMedlemskapsvurdering>()

    private val rapid =
        TestRapid().apply {
            MedlemskapsvurderingResultatRiver(
                this,
                transaksjon,
                object : ISpleisClient {
                    override fun hentMedlemskapsvurderinger(fødselsnummer: String): List<SpleisMedlemskapsvurdering> =
                        when (fødselsnummer) {
                            FØDSELSNUMMER ->
                                listOf(
                                    SpleisMedlemskapsvurdering.SpleisArbeidstaker(
                                        medlemskapsvurderingId = MedlemskapsvurderingId(vurderingIdSpleisArbeidstaker),
                                        opprettet = fellesOpprettet,
                                        skjæringstidspunkt = 1.februar,
                                        oppfylt = true,
                                        antallDager = 31,
                                        medlemskapsperiode = januar,
                                        arbeidsforhold = listOf(),
                                    ),
                                    SpleisMedlemskapsvurdering.SpleisArbeidstaker(
                                        medlemskapsvurderingId = MedlemskapsvurderingId(vurderingIdSpleisArbeidstakerIkkeOppfylt),
                                        opprettet = fellesOpprettet,
                                        skjæringstidspunkt = 1.februar,
                                        oppfylt = false,
                                        antallDager = 1,
                                        medlemskapsperiode = 31.januar.somPeriode(),
                                        arbeidsforhold = listOf(),
                                    ),
                                    SpleisMedlemskapsvurdering.SpleisSelvstendig(
                                        medlemskapsvurderingId = MedlemskapsvurderingId(vurderingIdSpleisSelvstendig),
                                        opprettet = fellesOpprettet,
                                        skjæringstidspunkt = 1.februar,
                                    ),
                                    SpleisMedlemskapsvurdering.InfotrygdArbeidstaker(
                                        medlemskapsvurderingId = MedlemskapsvurderingId(vurderingIdSpleisInfotrygd),
                                        opprettet = fellesOpprettet,
                                        skjæringstidspunkt = 1.februar,
                                    ),
                                ) + ekstraSpleisvurderinger
                            else -> emptyList()
                        }
                },
            )
        }

    private fun JsonNode.ok() =
        this
            .path("@løsning")
            .path("MedlemskapsvurderingResultat")
            .path("ok")
            .asBoolean()

    @Test
    fun `ikke oppfylt hos Spleis gir ok = false`() {
        rapid.sendTestMessage(
            medlemskapsvurderingResultatBehov(fnr = FØDSELSNUMMER, medlemskapsvurderingId = vurderingIdSpleisArbeidstakerIkkeOppfylt),
        )
        assertEquals(1, rapid.inspektør.size)
        val løsning = rapid.inspektør.message(0)
        assertFalse(løsning.ok())
    }

    @Test
    fun `oppfylt for arbeidstaker hos Spleis gir ok = true`() {
        rapid.sendTestMessage(
            medlemskapsvurderingResultatBehov(fnr = FØDSELSNUMMER, medlemskapsvurderingId = vurderingIdSpleisArbeidstaker),
        )
        assertEquals(1, rapid.inspektør.size)
        val løsning = rapid.inspektør.message(0)
        assertTrue(løsning.ok())
    }

    @Test
    fun `selvstendig hos Spleis gir ok = true`() {
        rapid.sendTestMessage(
            medlemskapsvurderingResultatBehov(fnr = FØDSELSNUMMER, medlemskapsvurderingId = vurderingIdSpleisSelvstendig),
        )
        assertEquals(1, rapid.inspektør.size)
        val løsning = rapid.inspektør.message(0)
        assertTrue(løsning.ok())
    }

    @Test
    fun `infotrygd hos Spleis gir ok = true`() {
        rapid.sendTestMessage(
            medlemskapsvurderingResultatBehov(fnr = FØDSELSNUMMER, medlemskapsvurderingId = vurderingIdSpleisInfotrygd),
        )
        assertEquals(1, rapid.inspektør.size)
        val løsning = rapid.inspektør.message(0)
        assertTrue(løsning.ok())
    }

    @Test
    fun `lokal vurdering brukes framfor Spleis ved uenighet`() {
        val lokal =
            Medlemskapsvurdering
                .avSaksbehandler(
                    fødselsnummer = FØDSELSNUMMER,
                    skjæringstidspunkt = 1.februar,
                    vilkårsvurdering =
                        Vilkårsvurdering.avSaksbehandler(
                            vilkårskode = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                            utfall = Utfall.IkkeOppfylt,
                            saksbehandlerIdent = "Z999999",
                            fritekstbegrunnelse = "",
                        ),
                ).also { transaksjon.medlemskapsvurderinger.lagre(it) }
        ekstraSpleisvurderinger +=
            SpleisMedlemskapsvurdering.SpleisArbeidstaker(
                medlemskapsvurderingId = lokal.id,
                opprettet = fellesOpprettet,
                skjæringstidspunkt = 1.februar,
                oppfylt = true,
                antallDager = 31,
                medlemskapsperiode = januar,
                arbeidsforhold = listOf(),
            )

        rapid.sendTestMessage(
            medlemskapsvurderingResultatBehov(fnr = FØDSELSNUMMER, medlemskapsvurderingId = lokal.id.value),
        )
        assertEquals(1, rapid.inspektør.size)
        assertFalse(rapid.inspektør.message(0).ok())
    }

    @Test
    fun `tom liste for FNR fra Spleis gir ikke svar`() {
        rapid.sendTestMessage(
            medlemskapsvurderingResultatBehov(fnr = "ukjent", medlemskapsvurderingId = UUID.randomUUID()),
        )
        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `ukjent medlemskapsvurderingId for Spleis gir ikke svar`() {
        rapid.sendTestMessage(
            medlemskapsvurderingResultatBehov(fnr = FØDSELSNUMMER, medlemskapsvurderingId = UUID.randomUUID()),
        )
        assertEquals(0, rapid.inspektør.size)
    }

    private companion object {
        const val FØDSELSNUMMER = "12029240045"

        @Language("JSON")
        fun medlemskapsvurderingResultatBehov(
            fnr: String = "01018099999",
            medlemskapsvurderingId: UUID,
        ) = """
        {
          "@event_name": "behov",
          "@id": "${UUID.randomUUID()}",
          "@behov": ["MedlemskapsvurderingResultat"],
          "fødselsnummer": "$fnr",
          "MedlemskapsvurderingResultat": {
            "medlemskapsvurderingId": "$medlemskapsvurderingId"
          }
        }
        """
    }
}
