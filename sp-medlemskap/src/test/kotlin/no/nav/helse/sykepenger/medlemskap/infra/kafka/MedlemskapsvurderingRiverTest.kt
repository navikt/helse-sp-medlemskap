package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.helse.februar
import no.nav.helse.januar
import no.nav.helse.sykepenger.medlemskap.application.InMemoryTransaksjonProvider
import no.nav.helse.sykepenger.medlemskap.domain.Grunnlagsbehov
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.til
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class MedlemskapsvurderingRiverTest {
    private val transaksjon = InMemoryTransaksjonProvider()
    private val rapid =
        TestRapid().apply {
            MedlemskapsvurderingRiver(this, transaksjon)
        }

    @Test
    fun `uten vurdering bes det om medlemskap for perioden spleis oppgir`() {
        val vedtaksperiodeId = UUID.randomUUID()
        rapid.sendTestMessage(medlemskapsvurderingBehov(1.februar, 1.februar, 28.februar, vedtaksperiodeId = vedtaksperiodeId), "123")

        assertEquals(1, rapid.inspektør.size)
        assertEquals("123", rapid.inspektør.key(0))
        val behov = rapid.inspektør.message(0)
        val prøving = transaksjon.medlemskapsprøvinger.allePrøvinger.single()
        assertEquals(listOf("Medlemskap"), behov.path("@behov").toList().map { it.asString() })
        assertEquals(FØDSELSNUMMER, behov.path("fødselsnummer").asString())
        assertEquals(vedtaksperiodeId.toString(), behov.path("vedtaksperiodeId").asString())
        assertEquals("2018-02-01", behov.path("skjæringstidspunkt").asString())
        assertEquals(prøving.id.toString(), behov.path("medlemskapsprøvingId").asString())
        assertEquals("2018-02-01", behov.path("Medlemskap").path("skjæringstidspunkt").asString())
        assertEquals("2018-02-01", behov.path("Medlemskap").path("medlemskapPeriodeFom").asString())
        assertEquals("2018-02-28", behov.path("Medlemskap").path("medlemskapPeriodeTom").asString())
        assertEquals(
            "Medlemskapsvurdering",
            behov
                .path("opprinneligBehov")
                .path("@behov")
                .single()
                .asString(),
        )
        assertFalse(behov.has("saksbehandlerIdent"))

        assertEquals(Grunnlagsbehov.Medlemskap, prøving.uteståendeBehov)
        assertEquals(1.februar til 28.februar, prøving.oppslagsperiode)
        assertEquals(0, transaksjon.medlemskapsvurderinger.antallLagringer)
    }

    @Test
    fun `gjentatt behov mens prøvingen pågår ber om samme prøving igjen`() {
        rapid.sendTestMessage(medlemskapsvurderingBehov(1.februar, 1.februar, 28.februar))
        rapid.sendTestMessage(medlemskapsvurderingBehov(1.februar, 1.februar, 28.februar))

        assertEquals(2, rapid.inspektør.size)
        assertEquals(
            rapid.inspektør.message(0).path("medlemskapsprøvingId"),
            rapid.inspektør.message(1).path("medlemskapsprøvingId"),
        )
        assertEquals(1, transaksjon.medlemskapsprøvinger.allePrøvinger.size)
    }

    @Test
    fun `eksisterende vurdering løses umiddelbart`() {
        val vurdering =
            Medlemskapsvurdering.overførtFraSpleis(MedlemskapsvurderingId.ny(), FØDSELSNUMMER, 1.februar, Medlemskapsstatus.Ja, Instant.now())
        transaksjon.medlemskapsvurderinger.lagre(vurdering)

        rapid.sendTestMessage(medlemskapsvurderingBehov(1.februar, 1.februar, 28.februar))

        val løsning = rapid.inspektør.message(0)
        assertEquals(
            vurdering.id.toString(),
            løsning
                .path("@løsning")
                .path("Medlemskapsvurdering")
                .path("id")
                .asString(),
        )
        assertEquals(listOf("Medlemskapsvurdering"), løsning.path("@behov").toList().map { it.asString() })
        assertTrue(transaksjon.medlemskapsprøvinger.allePrøvinger.isEmpty())
    }

    @Test
    fun `ignorerer behov som allerede er løst`() {
        val løst =
            medlemskapsvurderingBehov(1.februar, 1.februar, 28.februar)
                .replace("\"@behov\"", "\"@løsning\": {\"Medlemskapsvurdering\": {\"id\": \"${UUID.randomUUID()}\"}}, \"@behov\"")

        rapid.sendTestMessage(løst)

        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `ignorerer behov uten oppslagsperiode`() {
        rapid.sendTestMessage(
            medlemskapsvurderingBehov(1.februar, 1.januar, 31.januar).replace("\"medlemskapPeriodeFom\": \"2018-01-01\",", ""),
        )

        assertEquals(0, rapid.inspektør.size)
        assertTrue(transaksjon.medlemskapsprøvinger.allePrøvinger.isEmpty())
    }
}
