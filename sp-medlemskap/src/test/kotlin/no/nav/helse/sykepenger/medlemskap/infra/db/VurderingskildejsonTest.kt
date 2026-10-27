package no.nav.helse.sykepenger.medlemskap.infra.db

import no.nav.helse.februar
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.domain.Vurderingskilde
import no.nav.helse.sykepenger.medlemskap.domain.Årsak
import no.nav.helse.til
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID
import kotlin.test.assertEquals

internal class VurderingskildejsonTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `alle kilder overlever en rundtur`() {
        listOf(
            Vurderingskilde.Automatisk(MedlemskapsprøvingId.ny(), 1.februar til 28.februar, "versjon"),
            Vurderingskilde.Automatisk(MedlemskapsprøvingId.ny(), 1.februar til 28.februar, "versjon", emptyList()),
            Vurderingskilde.Automatisk(MedlemskapsprøvingId.ny(), 1.februar til 28.februar, "versjon", listOf(Årsak("REGEL_3", "Forklaring"))),
            Vurderingskilde.Saksbehandler("A123456", "Begrunnelse", listOf("1")),
            Vurderingskilde.OverførtFraSpleis,
            Vurderingskilde.OverførtFraInfotrygd,
        ).forEach { kilde ->
            assertEquals(kilde, Vurderingskildejson.fraJson(Vurderingskildejson.tilJson(kilde)))
        }
    }

    // Formatet ligger i databasen, så det må ikke endres uten migrering
    @Test
    fun `automatisk kilde har stabilt format`() {
        val prøvingId = UUID.fromString("6f1b8c8e-1d2c-4f7a-9a51-0d4c9b1e2f3a")

        val json = Vurderingskildejson.tilJson(Vurderingskilde.Automatisk(MedlemskapsprøvingId(prøvingId), 1.februar til 28.februar, "versjon"))

        assertEquals(
            objectMapper.readTree(
                """{"type":"AUTOMATISK","prøvingId":"$prøvingId","oppslagFom":"2018-02-01","oppslagTom":"2018-02-28","versjonAvKildekode":"versjon"}""",
            ),
            objectMapper.readTree(json),
        )
    }

    @Test
    fun `automatisk kilde med årsaker har stabilt format`() {
        val prøvingId = UUID.fromString("6f1b8c8e-1d2c-4f7a-9a51-0d4c9b1e2f3a")

        val json =
            Vurderingskildejson.tilJson(
                Vurderingskilde.Automatisk(MedlemskapsprøvingId(prøvingId), 1.februar til 28.februar, "versjon", listOf(Årsak("REGEL_3", "Forklaring"))),
            )

        assertEquals(
            objectMapper.readTree(
                """{"type":"AUTOMATISK","prøvingId":"$prøvingId","oppslagFom":"2018-02-01","oppslagTom":"2018-02-28","versjonAvKildekode":"versjon",
                   "aarsaker":[{"regelId":"REGEL_3","forklaringPaaUavklart":"Forklaring"}]}""",
            ),
            objectMapper.readTree(json),
        )
    }

    @Test
    fun `automatisk kilde lagret før årsaker fantes kan leses`() {
        val prøvingId = UUID.fromString("6f1b8c8e-1d2c-4f7a-9a51-0d4c9b1e2f3a")

        val kilde =
            Vurderingskildejson.fraJson(
                """{"type":"AUTOMATISK","prøvingId":"$prøvingId","oppslagFom":"2018-02-01","oppslagTom":"2018-02-28","versjonAvKildekode":"versjon"}""",
            )

        assertEquals(Vurderingskilde.Automatisk(MedlemskapsprøvingId(prøvingId), 1.februar til 28.februar, "versjon", null), kilde)
    }

    @Test
    fun `overførte kilder er bare en type`() {
        assertEquals(objectMapper.readTree("""{"type":"OVERFOERT_FRA_SPLEIS"}"""), objectMapper.readTree(Vurderingskildejson.tilJson(Vurderingskilde.OverførtFraSpleis)))
    }
}
