package no.nav.helse.sykepenger.medlemskap.infra.db

import no.nav.helse.februar
import no.nav.helse.januar
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Vurderingskilde
import no.nav.helse.sykepenger.medlemskap.domain.Årsak
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostgresMedlemskapsvurderingRepositoryTest : DatabaseTest() {
    @ParameterizedTest
    @EnumSource(Medlemskapsstatus::class)
    fun `automatisk vurdering lagres og hentes tilbake`(status: Medlemskapsstatus) {
        val prøving = nyPrøving()
        val vurdering = prøving.motta(status, versjonAvKode = "versjon")
        lagre(vurdering)

        val lagret = transaksjon { it.medlemskapsvurderinger.finn(vurdering.id) }!!

        assertEquals(vurdering.copy(vurdertTidspunkt = lagret.vurdertTidspunkt), lagret)
        assertEquals(vurdering.vurdertTidspunkt.truncatedTo(ChronoUnit.MILLIS), lagret.vurdertTidspunkt.truncatedTo(ChronoUnit.MILLIS))
        assertEquals(Vurderingskilde.Automatisk(prøving.id, OPPSLAGSPERIODE, "versjon"), lagret.kilde)
    }

    @Test
    fun `automatisk vurdering lagres med årsaker`() {
        val aarsaker = listOf(Årsak("REGEL_3", "Forklaring"), Årsak("REGEL_19", "Annen forklaring"))
        val vurdering = nyPrøving().motta(Medlemskapsstatus.Uavklart, versjonAvKode = "versjon", aarsaker = aarsaker)
        lagre(vurdering)

        val lagret = transaksjon { it.medlemskapsvurderinger.finn(vurdering.id) }!!

        assertEquals(aarsaker, (lagret.kilde as Vurderingskilde.Automatisk).aarsaker)
    }

    @Test
    fun `manuell vurdering lagres med begrunnelse og journalposter`() {
        val vurdering =
            Medlemskapsvurdering.avSaksbehandler(FØDSELSNUMMER, 1.februar, Medlemskapsstatus.Nei, "A123456", "Begrunnelse", listOf("1", "2"))
        lagre(vurdering)

        val lagret = transaksjon { it.medlemskapsvurderinger.finn(vurdering.id) }!!

        assertEquals(Medlemskapsstatus.Nei, lagret.status)
        assertEquals(Vurderingskilde.Saksbehandler("A123456", "Begrunnelse", listOf("1", "2")), lagret.kilde)
    }

    @Test
    fun `overførte vurderinger lagres med kilden sin`() {
        val spleis = Medlemskapsvurdering.overførtFraSpleis(MedlemskapsvurderingId.ny(), FØDSELSNUMMER, 1.februar, Medlemskapsstatus.Uavklart, Instant.now())
        val infotrygd = Medlemskapsvurdering.overførtFraInfotrygd(MedlemskapsvurderingId.ny(), FØDSELSNUMMER, 1.januar, Instant.now())
        lagre(spleis)
        lagre(infotrygd)

        assertEquals(Vurderingskilde.OverførtFraSpleis, transaksjon { it.medlemskapsvurderinger.finn(spleis.id) }!!.kilde)
        assertEquals(Medlemskapsstatus.Uavklart, transaksjon { it.medlemskapsvurderinger.finn(spleis.id) }!!.status)
        assertEquals(Vurderingskilde.OverførtFraInfotrygd, transaksjon { it.medlemskapsvurderinger.finn(infotrygd.id) }!!.kilde)
    }

    @Test
    fun `vurderinger er immutable`() {
        val vurdering = nyPrøving().motta(Medlemskapsstatus.Ja)
        lagre(vurdering)

        assertThrows<IllegalStateException> { lagre(vurdering) }
    }

    @Test
    fun `gjeldende er den sist vurderte og historikken er nyeste først`() {
        val eldst = overført(Instant.parse("2026-01-01T00:00:00Z"), Medlemskapsstatus.Nei)
        val nyest = overført(Instant.parse("2026-03-01T00:00:00Z"), Medlemskapsstatus.Ja)
        val midten = overført(Instant.parse("2026-02-01T00:00:00Z"), Medlemskapsstatus.Uavklart)
        listOf(eldst, nyest, midten).forEach(::lagre)
        lagre(overført(Instant.parse("2026-04-01T00:00:00Z"), Medlemskapsstatus.Ja, fødselsnummer = ANNET_FØDSELSNUMMER))

        assertEquals(nyest.id, transaksjon { it.medlemskapsvurderinger.gjeldende(FØDSELSNUMMER, 1.februar) }!!.id)
        assertEquals(
            listOf(nyest.id, midten.id, eldst.id),
            transaksjon { it.medlemskapsvurderinger.historikk(FØDSELSNUMMER, 1.februar) }.map { it.id },
        )
    }

    @Test
    fun `ingen vurdering gir null og tom historikk`() {
        assertNull(transaksjon { it.medlemskapsvurderinger.gjeldende(FØDSELSNUMMER, 1.februar) })
        assertEquals(emptyList(), transaksjon { it.medlemskapsvurderinger.historikk(FØDSELSNUMMER, 1.februar) })
        assertNull(transaksjon { it.medlemskapsvurderinger.finn(MedlemskapsvurderingId.ny()) })
    }

    @Test
    fun `låsFor venter til en annen transaksjon som har låst samme person er ferdig`() {
        val harLåst = CountDownLatch(1)
        val slippLåsen = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val første =
                executor.submit {
                    transaksjon {
                        it.medlemskapsvurderinger.låsFor(FØDSELSNUMMER)
                        harLåst.countDown()
                        slippLåsen.await(10, TimeUnit.SECONDS)
                    }
                }
            assertTrue(harLåst.await(10, TimeUnit.SECONDS))

            val andre = executor.submit { transaksjon { it.medlemskapsvurderinger.låsFor(FØDSELSNUMMER) } }
            assertThrows<TimeoutException> { andre.get(500, TimeUnit.MILLISECONDS) }

            slippLåsen.countDown()
            første.get(10, TimeUnit.SECONDS)
            andre.get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `låsFor venter ikke på en annen person`() {
        transaksjon {
            it.medlemskapsvurderinger.låsFor(FØDSELSNUMMER)
            Executors.newSingleThreadExecutor().use { executor ->
                executor.submit { transaksjon { it.medlemskapsvurderinger.låsFor("01010112345") } }.get(10, TimeUnit.SECONDS)
            }
        }
    }

    private fun overført(
        vurdertTidspunkt: Instant,
        status: Medlemskapsstatus,
        fødselsnummer: String = FØDSELSNUMMER,
    ) = Medlemskapsvurdering.overførtFraSpleis(MedlemskapsvurderingId.ny(), fødselsnummer, 1.februar, status, vurdertTidspunkt)

    private fun lagre(vurdering: Medlemskapsvurdering) = transaksjon { it.medlemskapsvurderinger.lagre(vurdering) }
}
