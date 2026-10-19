package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.helse.februar
import no.nav.helse.sykepenger.medlemskap.application.HandlingType
import no.nav.helse.sykepenger.medlemskap.application.InMemoryTransaksjonProvider
import no.nav.helse.sykepenger.medlemskap.application.MedlemskapService
import no.nav.helse.sykepenger.medlemskap.application.OutboxMelding
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Vurderingskilde
import no.nav.helse.til
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class GrunnlagForMedlemskapsvurderingRiverTest {
    private val transaksjon = InMemoryTransaksjonProvider()
    private val rapid =
        TestRapid().apply {
            MedlemskapsvurderingRiver(this, transaksjon, "versjon")
            GrunnlagForAutomatiskMedlemskapsvurderingRiver(this, transaksjon, "versjon")
            GrunnlagForNyMedlemskapsvurderingRiver(this, transaksjon, "versjon")
        }

    @ParameterizedTest
    @CsvSource(
        "JA, Ja",
        "NEI, Nei",
        "UAVKLART, Uavklart",
        "UAVKLART_MED_BRUKERSPORSMAAL, UavklartMedBrukerspørsmål",
    )
    fun `svar fra sparkel-medlemskap gir vurdering og løser det opprinnelige behovet`(
        svar: String,
        forventetStatus: Medlemskapsstatus,
    ) {
        rapid.sendTestMessage(medlemskapsvurderingBehov(1.februar, 1.februar, 28.februar))
        val medlemskapsbehov = rapid.inspektør.message(0)

        rapid.sendTestMessage(løsMedlemskapsbehov(medlemskapsbehov, svar))

        assertEquals(2, rapid.inspektør.size)
        val løsning = rapid.inspektør.message(1)
        assertEquals(listOf("Medlemskapsvurdering"), løsning.path("@behov").toList().map { it.asString() })
        val id =
            MedlemskapsvurderingId(
                UUID.fromString(
                    løsning
                        .path("@løsning")
                        .path("Medlemskapsvurdering")
                        .path("id")
                        .asString(),
                ),
            )
        val vurdering = transaksjon.medlemskapsvurderinger.finn(id)!!
        assertEquals(forventetStatus, vurdering.status)
        val kilde = assertIs<Vurderingskilde.Automatisk>(vurdering.kilde)
        assertEquals(1.februar til 28.februar, kilde.oppslagsperiode)
        assertEquals("versjon", kilde.versjonAvKildekode)
    }

    @Test
    fun `samme løsning to ganger gir bare én vurdering`() {
        rapid.sendTestMessage(medlemskapsvurderingBehov(1.februar, 1.februar, 28.februar))
        val løst = løsMedlemskapsbehov(rapid.inspektør.message(0), "JA")

        rapid.sendTestMessage(løst)
        rapid.sendTestMessage(løst)

        assertEquals(2, rapid.inspektør.size)
        assertEquals(1, transaksjon.medlemskapsvurderinger.antallLagringer)
    }

    @Test
    fun `ignorerer løsninger som ikke er final`() {
        rapid.sendTestMessage(medlemskapsvurderingBehov(1.februar, 1.februar, 28.februar))
        val løst = jacksonObjectMapper().readTree(løsMedlemskapsbehov(rapid.inspektør.message(0), "JA")) as ObjectNode
        løst.remove("@final")

        rapid.sendTestMessage(løst.toString())

        assertEquals(1, rapid.inspektør.size)
        assertEquals(0, transaksjon.medlemskapsvurderinger.antallLagringer)
    }

    @Test
    fun `ignorerer medlemskapsløsninger som ikke er bestilt av sp-medlemskap`() {
        rapid.sendTestMessage(medlemskapsvurderingBehov(1.februar, 1.februar, 28.februar))
        val løst = jacksonObjectMapper().readTree(løsMedlemskapsbehov(rapid.inspektør.message(0), "JA")) as ObjectNode
        løst.remove("medlemskapsprøvingId")

        rapid.sendTestMessage(løst.toString())

        assertEquals(1, rapid.inspektør.size)
        assertEquals(0, transaksjon.medlemskapsvurderinger.antallLagringer)
    }

    @Test
    fun `ignorerer ukjente svar`() {
        rapid.sendTestMessage(medlemskapsvurderingBehov(1.februar, 1.februar, 28.februar))

        rapid.sendTestMessage(løsMedlemskapsbehov(rapid.inspektør.message(0), "KANSKJE"))

        assertEquals(1, rapid.inspektør.size)
        assertEquals(0, transaksjon.medlemskapsvurderinger.antallLagringer)
    }

    @Test
    fun `svar på ny vurdering bestilt av saksbehandler lagres og meldes via outbox`() {
        rapid.sendTestMessage(medlemskapsvurderingBehov(1.februar, 1.februar, 28.februar))
        rapid.sendTestMessage(løsMedlemskapsbehov(rapid.inspektør.message(0), "NEI"))
        val venter =
            assertIs<MedlemskapService.VurderMedlemskapPåNyttResultat.VenterPåGrunnlag>(
                MedlemskapService(transaksjon).vurderMedlemskapPåNytt(FØDSELSNUMMER, 1.februar),
            )
        val behov =
            Medlemskapsbehov.nytt(
                fødselsnummer = FØDSELSNUMMER,
                vedtaksperiodeId = venter.medlemskapsprøvingId.toString(),
                skjæringstidspunkt = 1.februar,
                medlemskapsprøvingId = venter.medlemskapsprøvingId,
                oppslagsperiode = venter.oppslagsperiode,
                ekstra = mapOf("saksbehandlerIdent" to "A123456"),
            )

        rapid.sendTestMessage(løsMedlemskapsbehov(jacksonObjectMapper().readTree(behov.toJson()), "JA"))

        assertEquals(2, rapid.inspektør.size, "Ny vurdering meldes via outbox, ikke direkte på rapid")
        val gjeldende = transaksjon.medlemskapsvurderinger.gjeldende(FØDSELSNUMMER, 1.februar)!!
        assertEquals(Medlemskapsstatus.Ja, gjeldende.status)
        val handling =
            transaksjon.outbox.upubliserteMeldinger
                .map { it.melding }
                .filterIsInstance<OutboxMelding.Saksbehandlerhandling>()
                .single()
        assertEquals(HandlingType.NY_AUTOMATISK_MEDLEMSKAPSVURDERING, handling.handlingType)
        assertEquals("A123456", handling.saksbehandlerIdent)
        assertTrue(
            transaksjon.outbox.upubliserteMeldinger.any {
                it.melding == OutboxMelding.MedlemskapsvurderingEndret(1.februar, gjeldende.id.value, manuellVurdering = false)
            },
        )
    }
}
