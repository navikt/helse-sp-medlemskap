package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.helse.februar
import no.nav.helse.januar
import no.nav.helse.sykepenger.medlemskap.domain.Grunnlagsbehov
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Vurderingskilde
import no.nav.helse.sykepenger.medlemskap.infra.db.Database
import no.nav.helse.sykepenger.medlemskap.infra.db.DatabaseTest
import no.nav.helse.sykepenger.medlemskap.infra.db.Lagret
import no.nav.helse.sykepenger.medlemskap.leaderelection.LeaderElection
import no.nav.helse.til
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Flyten der behovet fra Spleis venter på en forhåndsvurdering før medlemskapstjenesten spørres direkte. */
internal class VentPåForhåndsvurderingTest : DatabaseTest() {
    private val transaksjon = Database.transaksjonProvider
    private val klokke = FlyttbarKlokke(Instant.parse("2018-02-01T12:00:00Z"))
    private val ventetid = Duration.ofMinutes(5)
    private val rapid =
        TestRapid().apply {
            MedlemskapsvurderingRiver(this, transaksjon, "versjon", VentPåForhåndsvurdering(ventetid, klokke))
            GrunnlagForAutomatiskMedlemskapsvurderingRiver(this, transaksjon, "versjon")
            ForhåndsvurderingRiver(this, transaksjon)
        }
    private val jobb = VentendeBehovjobb(rapid, transaksjon, LeaderElection { true }, "versjon", klokke)

    private val skjæringstidspunkt = 1.februar
    private val gyldigFor = 18.januar til 19.februar
    private val søknadId = UUID.randomUUID()

    @Test
    fun `venter på forhåndsvurdering og svarer på det opprinnelige behovet når den kommer`() {
        val behovId = UUID.randomUUID()

        rapid.sendTestMessage(behov(id = behovId))

        assertEquals(0, rapid.inspektør.size)
        val prøving = Lagret.prøvinger().single()
        assertEquals(Grunnlagsbehov.Forhåndsvurdering, prøving.uteståendeBehov)
        val ventende = Lagret.ventendeBehov().single()
        assertEquals(klokke.instant().plus(ventetid), ventende.frist)

        jobb.kjørEnRunde()
        assertEquals(0, rapid.inspektør.size)

        rapid.sendTestMessage(forhåndsvurderingMelding(søknadId, gyldigFor, "JA"))
        jobb.kjørEnRunde()

        assertEquals(1, rapid.inspektør.size)
        assertEquals(FØDSELSNUMMER, rapid.inspektør.key(0))
        val løsning = rapid.inspektør.message(0)
        assertEquals(behovId.toString(), løsning.path("@id").asString())
        val vurdering = Lagret.vurderinger().single()
        assertEquals(
            vurdering.id.toString(),
            løsning
                .path("@løsning")
                .path("Medlemskapsvurdering")
                .path("id")
                .asString(),
        )
        assertEquals(Medlemskapsstatus.Ja, vurdering.status)
        assertEquals(søknadId, assertIs<Vurderingskilde.Automatisk>(vurdering.kilde).forhåndsvurdertSøknadId)
        assertTrue(Lagret.prøvinger().single().erAvsluttet)
        assertTrue(Lagret.ventendeBehov().isEmpty())
    }

    @Test
    fun `bruker forhåndsvurdering som allerede er mottatt`() {
        rapid.sendTestMessage(forhåndsvurderingMelding(søknadId, gyldigFor, "NEI"))

        rapid.sendTestMessage(behov())

        assertEquals(1, rapid.inspektør.size)
        val vurdering = Lagret.vurderinger().single()
        assertEquals(Medlemskapsstatus.Nei, vurdering.status)
        assertEquals(
            vurdering.id.toString(),
            rapid.inspektør
                .message(0)
                .path("@løsning")
                .path("Medlemskapsvurdering")
                .path("id")
                .asString(),
        )
        assertTrue(Lagret.ventendeBehov().isEmpty())
    }

    @Test
    fun `bruker forhåndsvurderingen av første søknad når den første perioden ble avsluttet uten utbetaling`() {
        // Søknad 1 (1.-14. februar) er innenfor arbeidsgiverperioden. Det er den medlemskapstjenesten
        // forhåndsvurderer, men Spleis vilkårsprøver først når søknad 2 (15.-28. februar) kommer.
        rapid.sendTestMessage(forhåndsvurderingMelding(søknadId, gyldigFor, "JA"))
        klokke.flytt(Duration.ofDays(14))

        rapid.sendTestMessage(medlemskapsvurderingBehov(skjæringstidspunkt, 15.februar, 28.februar))

        assertEquals(1, rapid.inspektør.size)
        val vurdering = Lagret.vurderinger().single()
        assertEquals(søknadId, assertIs<Vurderingskilde.Automatisk>(vurdering.kilde).forhåndsvurdertSøknadId)
    }

    @Test
    fun `bruker forhåndsvurdering når skjæringstidspunktet er innenfor perioden vurderingen gjelder for`() {
        rapid.sendTestMessage(forhåndsvurderingMelding(søknadId, 16.januar til 16.februar, "JA"))

        rapid.sendTestMessage(behov())

        assertEquals(1, rapid.inspektør.size)
        val vurdering = Lagret.vurderinger().single()
        assertEquals(søknadId, assertIs<Vurderingskilde.Automatisk>(vurdering.kilde).forhåndsvurdertSøknadId)
    }

    @Test
    fun `venter når skjæringstidspunktet er utenfor perioden vurderingen gjelder for`() {
        rapid.sendTestMessage(forhåndsvurderingMelding(søknadId, 1.januar til 31.januar, "JA"))

        rapid.sendTestMessage(behov())

        assertEquals(0, rapid.inspektør.size)
        assertEquals(1, Lagret.ventendeBehov().size)
    }

    @Test
    fun `bruker den sist mottatte forhåndsvurderingen for skjæringstidspunktet`() {
        val sisteSøknadId = UUID.randomUUID()
        rapid.sendTestMessage(forhåndsvurderingMelding(søknadId, gyldigFor, "UAVKLART_MED_BRUKERSPORSMAAL"))
        rapid.sendTestMessage(forhåndsvurderingMelding(sisteSøknadId, gyldigFor, "JA"))

        rapid.sendTestMessage(behov())

        val vurdering = Lagret.vurderinger().single()
        assertEquals(Medlemskapsstatus.Ja, vurdering.status)
        assertEquals(sisteSøknadId, assertIs<Vurderingskilde.Automatisk>(vurdering.kilde).forhåndsvurdertSøknadId)
    }

    @Test
    fun `bruker ikke forhåndsvurdering for et annet skjæringstidspunkt eller en annen person`() {
        rapid.sendTestMessage(forhåndsvurderingMelding(søknadId, 2.februar til 28.februar, "JA"))
        rapid.sendTestMessage(forhåndsvurderingMelding(UUID.randomUUID(), gyldigFor, "JA", fødselsnummer = "01010112345"))
        rapid.sendTestMessage(forhåndsvurderingMelding(UUID.randomUUID(), null, "JA"))

        rapid.sendTestMessage(behov())
        jobb.kjørEnRunde()

        assertEquals(0, rapid.inspektør.size)
        assertEquals(1, Lagret.ventendeBehov().size)
    }

    @Test
    fun `spør medlemskapstjenesten direkte når fristen går ut`() {
        val vedtaksperiodeId = UUID.randomUUID()
        rapid.sendTestMessage(behov(vedtaksperiodeId = vedtaksperiodeId))

        klokke.flytt(ventetid.minusSeconds(1))
        jobb.kjørEnRunde()
        assertEquals(0, rapid.inspektør.size)

        klokke.flytt(Duration.ofSeconds(1))
        jobb.kjørEnRunde()

        assertEquals(1, rapid.inspektør.size)
        val medlemskapsbehov = rapid.inspektør.message(0)
        val prøving = Lagret.prøvinger().single()
        assertEquals(listOf("Medlemskap"), medlemskapsbehov.path("@behov").toList().map { it.asString() })
        assertEquals(prøving.id.toString(), medlemskapsbehov.path("medlemskapsprøvingId").asString())
        assertEquals(vedtaksperiodeId.toString(), medlemskapsbehov.path("vedtaksperiodeId").asString())
        assertEquals(
            "Medlemskapsvurdering",
            medlemskapsbehov
                .path("opprinneligBehov")
                .path("@behov")
                .single()
                .asString(),
        )
        assertEquals(Grunnlagsbehov.Medlemskap, prøving.uteståendeBehov)
        assertTrue(Lagret.ventendeBehov().isEmpty())

        // En forhåndsvurdering som kommer for sent, brukes ikke
        rapid.sendTestMessage(forhåndsvurderingMelding(søknadId, gyldigFor, "JA"))
        jobb.kjørEnRunde()
        assertEquals(1, rapid.inspektør.size)

        rapid.sendTestMessage(løsMedlemskapsbehov(medlemskapsbehov, "UAVKLART_MED_BRUKERSPORSMAAL"))

        val vurdering = Lagret.vurderinger().single()
        assertEquals(Medlemskapsstatus.UavklartMedBrukerspørsmål, vurdering.status)
        assertNull(assertIs<Vurderingskilde.Automatisk>(vurdering.kilde).forhåndsvurdertSøknadId)
        assertTrue(Lagret.prøvinger().single().erAvsluttet)
    }

    @Test
    fun `nytt behov mens prøvingen venter erstatter det som venter`() {
        rapid.sendTestMessage(behov())
        val nyttBehovId = UUID.randomUUID()
        rapid.sendTestMessage(behov(id = nyttBehovId))

        assertEquals(0, rapid.inspektør.size)
        assertEquals(1, Lagret.prøvinger().size)
        val ventende = Lagret.ventendeBehov().single()
        assertEquals(nyttBehovId.toString(), jacksonObjectMapper().readTree(ventende.opprinneligBehov).path("@id").asString())
    }

    @Test
    fun `nytt behov etter at vurderingen er gjort, gir samme vurdering`() {
        rapid.sendTestMessage(forhåndsvurderingMelding(søknadId, gyldigFor, "JA"))
        rapid.sendTestMessage(behov())
        rapid.sendTestMessage(behov())

        assertEquals(2, rapid.inspektør.size)
        assertEquals(1, Lagret.antallVurderinger())
    }

    @Test
    fun `løsning på Medlemskap-behov avvises mens prøvingen venter på forhåndsvurdering`() {
        rapid.sendTestMessage(behov())
        val prøving = Lagret.prøvinger().single()

        val falsktBehov =
            Medlemskapsbehov.nytt(FØDSELSNUMMER, "vedtaksperiode", skjæringstidspunkt, prøving.id, prøving.oppslagsperiode)
        rapid.sendTestMessage(løsMedlemskapsbehov(jacksonObjectMapper().readTree(falsktBehov.toJson()), "JA"))

        assertEquals(Grunnlagsbehov.Forhåndsvurdering, Lagret.prøvinger().single().uteståendeBehov)
        assertEquals(0, Lagret.antallVurderinger())
    }

    @Test
    fun `spør direkte når behovet ikke gjelder en arbeidstaker`() {
        rapid.sendTestMessage(forhåndsvurderingMelding(søknadId, gyldigFor, "JA"))

        rapid.sendTestMessage(behov(yrkesaktivitetstype = "SELVSTENDIG"))

        assertMedlemskapsbehovUtenVenting()
    }

    @Test
    fun `jobben gjør ingenting når den ikke er leader`() {
        rapid.sendTestMessage(behov())
        klokke.flytt(ventetid)

        VentendeBehovjobb(rapid, transaksjon, LeaderElection { false }, "versjon", klokke).kjørEnRundeHvisLeader()

        assertEquals(0, rapid.inspektør.size)
        assertEquals(1, Lagret.ventendeBehov().size)
    }

    private fun assertMedlemskapsbehovUtenVenting() {
        assertEquals(1, rapid.inspektør.size)
        assertEquals(
            listOf("Medlemskap"),
            rapid.inspektør
                .message(0)
                .path("@behov")
                .toList()
                .map { it.asString() },
        )
        assertEquals(
            Grunnlagsbehov.Medlemskap,
            Lagret
                .prøvinger()
                .single()
                .uteståendeBehov,
        )
        assertTrue(Lagret.ventendeBehov().isEmpty())
    }

    private fun behov(
        id: UUID = UUID.randomUUID(),
        vedtaksperiodeId: UUID = UUID.randomUUID(),
        yrkesaktivitetstype: String = "ARBEIDSTAKER",
    ) = medlemskapsvurderingBehov(
        skjæringstidspunkt,
        skjæringstidspunkt,
        28.februar,
        vedtaksperiodeId = vedtaksperiodeId,
        yrkesaktivitetstype = yrkesaktivitetstype,
        id = id,
    )

    private class FlyttbarKlokke(
        private var nå: Instant,
    ) : Clock() {
        fun flytt(varighet: Duration) {
            nå = nå.plus(varighet)
        }

        override fun instant() = nå

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId?) = this
    }
}
