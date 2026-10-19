package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.helse.februar
import no.nav.helse.sykepenger.medlemskap.application.InMemoryTransaksjonProvider
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.Utfall
import no.nav.helse.sykepenger.medlemskap.domain.Vilkårskode
import no.nav.helse.sykepenger.medlemskap.domain.Vilkårsvurdering
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID
import java.util.stream.Stream

internal class MedlemskapsvurderingResultatRiverTest {
    private val transaksjon = InMemoryTransaksjonProvider()
    private val repository = transaksjon.medlemskapsvurderinger
    private val rapid =
        TestRapid().apply {
            MedlemskapsvurderingResultatRiver(
                this,
                transaksjon,
                object : ISpleisClient {
                    override fun hentMedlemskapsvurderinger(fødselsnummer: String): List<SpleisMedlemskapsvurdering> = error("Spleis skal ikke kalles når lokal vurdering finnes")
                },
            )
        }

    @Test
    fun `oppfylt vurdering gir ok = true`() {
        val vurdering = manuellVurdering(utfall = Utfall.Oppfylt)

        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(vurdering.id.value))

        assertEquals(1, rapid.inspektør.size)
        val løsning = rapid.inspektør.message(0)
        assertTrue(
            løsning
                .path("@løsning")
                .path("MedlemskapsvurderingResultat")
                .path("ok")
                .asBoolean(),
        )
    }

    @Test
    fun `ikke-oppfylt vurdering gir ok = false`() {
        val vurdering = manuellVurdering(utfall = Utfall.IkkeOppfylt)

        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(vurdering.id.value))

        assertEquals(1, rapid.inspektør.size)
        val løsning = rapid.inspektør.message(0)
        assertFalse(
            løsning
                .path("@løsning")
                .path("MedlemskapsvurderingResultat")
                .path("ok")
                .asBoolean(),
        )
    }

    @ParameterizedTest
    @MethodSource("medlemskapsVilkårskoder")
    fun `oppfylt uansett avgjørende vilkårskode gir ok = true`(vilkårskode: Vilkårskode) {
        rapid.reset()
        val vurdering = manuellVurdering(vilkårskode = vilkårskode, utfall = Utfall.Oppfylt)

        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(vurdering.id.value))

        val løsning = rapid.inspektør.message(0)
        assertTrue(
            løsning
                .path("@løsning")
                .path("MedlemskapsvurderingResultat")
                .path("ok")
                .asBoolean(),
        ) {
            "$vilkårskode oppfylt skal gi ok=true"
        }
    }

    @ParameterizedTest
    @MethodSource("medlemskapsVilkårskoder")
    fun `ikke oppfylt uansett avgjørende vilkårskode gir ok = false`(vilkårskode: Vilkårskode) {
        rapid.reset()
        val vurdering = manuellVurdering(vilkårskode = vilkårskode, utfall = Utfall.IkkeOppfylt)

        rapid.sendTestMessage(medlemskapsvurderingResultatBehov(vurdering.id.value))

        val løsning = rapid.inspektør.message(0)
        assertFalse(
            løsning
                .path("@løsning")
                .path("MedlemskapsvurderingResultat")
                .path("ok")
                .asBoolean(),
        ) {
            "$vilkårskode ikke oppfylt skal gi ok=false"
        }
    }

    @Test
    fun `behov uten medlemskapsvurderingId ignoreres`() {
        @Language("JSON")
        val melding = """
        {
          "@event_name": "behov",
          "@id": "${UUID.randomUUID()}",
          "@behov": ["MedlemskapsvurderingResultat"],
          "MedlemskapsvurderingResultat": {}
        }
        """
        rapid.sendTestMessage(melding)

        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `annet behov ignoreres`() {
        @Language("JSON")
        val melding = """
        {
          "@event_name": "behov",
          "@id": "${UUID.randomUUID()}",
          "@behov": ["EtHeltAnnetBehov"],
          "MedlemskapsvurderingResultat": {
            "medlemskapsvurderingId": "${UUID.randomUUID()}"
          }
        }
        """
        rapid.sendTestMessage(melding)

        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `melding med feil event_name ignoreres`() {
        @Language("JSON")
        val melding = """
        {
          "@event_name": "løsning",
          "@id": "${UUID.randomUUID()}",
          "@behov": ["MedlemskapsvurderingResultat"],
          "MedlemskapsvurderingResultat": {
            "medlemskapsvurderingId": "${UUID.randomUUID()}"
          }
        }
        """
        rapid.sendTestMessage(melding)

        assertEquals(0, rapid.inspektør.size)
    }

    private fun manuellVurdering(
        vilkårskode: Vilkårskode = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
        utfall: Utfall,
    ): Medlemskapsvurdering.VurdertISpVilkårsprøving {
        val vilkårsvurdering =
            Vilkårsvurdering.avSaksbehandler(
                vilkårskode = vilkårskode,
                utfall = utfall,
                saksbehandlerIdent = "Z999999",
                fritekstbegrunnelse = "",
            )
        return Medlemskapsvurdering
            .avSaksbehandler(
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
                vilkårsvurdering = vilkårsvurdering,
            ).also { repository.lagre(it) }
    }

    private companion object {
        const val FØDSELSNUMMER = "12029240045"

        @JvmStatic
        fun medlemskapsVilkårskoder(): Stream<Vilkårskode> = Vilkårskode.entries.stream()

        @Language("JSON")
        fun medlemskapsvurderingResultatBehov(medlemskapsvurderingId: UUID) =
            """
        {
          "@event_name": "behov",
          "@id": "${UUID.randomUUID()}",
          "@behov": ["MedlemskapsvurderingResultat"],
          "fødselsnummer": "01018099999",
          "MedlemskapsvurderingResultat": {
            "medlemskapsvurderingId": "$medlemskapsvurderingId"
          }
        }
        """
    }
}
