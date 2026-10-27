package no.nav.helse.sykepenger.medlemskap.infra.rest

import no.nav.helse.februar
import no.nav.helse.januar
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsprøving
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Årsak
import no.nav.helse.til
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import kotlin.test.assertEquals

internal class VurderingsresponsTest {
    @Test
    fun `automatisk vurdering viser oppslagsperioden`() {
        val vurdering = Medlemskapsprøving.start(FNR, 1.februar, 1.januar til 31.januar).motta(Medlemskapsstatus.Ja, "versjon")

        val respons = Vurderingsrespons.fra(vurdering)

        assertEquals(1.februar, respons.skjæringstidspunkt)
        val krav = respons.krav.single()
        assertEquals(vurdering.id.value, krav.id)
        assertEquals(ApiKravkode.MEDLEMSKAP, krav.kravkode)
        assertEquals(ApiVurderingskilde.Automatisk("versjon", ApiPeriode(1.januar, 31.januar)), krav.kilde)
    }

    @Test
    fun `automatisk vurdering viser årsakene`() {
        val vurdering =
            Medlemskapsprøving
                .start(FNR, 1.februar, 1.januar til 31.januar)
                .motta(Medlemskapsstatus.Uavklart, "versjon", listOf(Årsak("REGEL_3", "Forklaring")))

        val krav = Vurderingsrespons.fra(vurdering).krav.single()

        assertEquals(
            ApiVurderingskilde.Automatisk("versjon", ApiPeriode(1.januar, 31.januar), listOf(ApiÅrsak("REGEL_3", "Forklaring"))),
            krav.kilde,
        )
    }

    @ParameterizedTest
    @CsvSource(
        "Ja, JA, true",
        "Nei, NEI, false",
        "Uavklart, UAVKLART, true",
        "UavklartMedBrukerspørsmål, UAVKLART_MED_BRUKERSPORSMAAL, true",
    )
    fun `status og ok`(
        status: Medlemskapsstatus,
        forventetStatus: ApiMedlemskapsstatus,
        forventetOk: Boolean,
    ) {
        val vurdering = Medlemskapsvurdering.overførtFraSpleis(MedlemskapsvurderingId.ny(), FNR, 1.februar, status, Instant.now())

        val krav = Vurderingsrespons.fra(vurdering).krav.single()

        assertEquals(forventetStatus, krav.status)
        assertEquals(forventetOk, krav.medlemskapOk)
        assertEquals(ApiVurderingskilde.OverførtFraSpleis(), krav.kilde)
    }

    @Test
    fun `manuell vurdering har saksbehandler som kilde`() {
        val vurdering = Medlemskapsvurdering.avSaksbehandler(FNR, 1.februar, Medlemskapsstatus.Nei, "A123456", "Begrunnelse", listOf("1"))

        val krav = Vurderingsrespons.fra(vurdering).krav.single()

        assertEquals(ApiVurderingskilde.Saksbehandler("A123456", "Begrunnelse", listOf("1")), krav.kilde)
        assertEquals(vurdering.vurdertTidspunkt, krav.vurdertTidspunkt)
    }

    @Test
    fun `infotrygd vises som overført fra infotrygd`() {
        val vurdering = Medlemskapsvurdering.overførtFraInfotrygd(MedlemskapsvurderingId.ny(), FNR, 1.februar, Instant.now())

        assertEquals(
            ApiVurderingskilde.OverførtFraInfotrygd(),
            Vurderingsrespons
                .fra(vurdering)
                .krav
                .single()
                .kilde,
        )
    }

    @Test
    fun `historikken beholder rekkefølgen den får`() {
        val nyest = Medlemskapsvurdering.overførtFraSpleis(MedlemskapsvurderingId.ny(), FNR, 1.februar, Medlemskapsstatus.Ja, Instant.now())
        val eldst = Medlemskapsvurdering.overførtFraSpleis(MedlemskapsvurderingId.ny(), FNR, 1.februar, Medlemskapsstatus.Nei, Instant.EPOCH)

        val historikk = Vurderingsrespons.historikk(1.februar, listOf(nyest, eldst))

        assertEquals(listOf(nyest.id.value, eldst.id.value), historikk.historikk.map { it.medlemskapsvurdering.id })
        assertEquals(listOf(nyest.vurdertTidspunkt, eldst.vurdertTidspunkt), historikk.historikk.map { it.vurdertTidspunkt })
    }

    private companion object {
        const val FNR = "12029240045"
    }
}
