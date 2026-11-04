package no.nav.helse.sykepenger.medlemskap.infra.db

import no.nav.helse.Periode
import no.nav.helse.februar
import no.nav.helse.januar
import no.nav.helse.sykepenger.medlemskap.domain.Forhåndsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.ForhåndsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Årsak
import no.nav.helse.til
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class PostgresForhåndsvurderingRepositoryTest : DatabaseTest() {
    @Test
    fun `lagrer og henter forhåndsvurdering med datoer`() {
        val forhåndsvurdering = forhåndsvurdering(aarsaker = listOf(Årsak("REGEL_3", "Forklaring")))
        transaksjon { it.forhåndsvurderinger.lagre(forhåndsvurdering, """{"soknadId":"${forhåndsvurdering.søknadId}"}""") }

        assertEquals(listOf(forhåndsvurdering), transaksjon { it.forhåndsvurderinger.finn(FØDSELSNUMMER) })
    }

    @Test
    fun `lagrer forhåndsvurdering uten datoer`() {
        val forhåndsvurdering = forhåndsvurdering(gyldigFor = null, aarsaker = null)
        transaksjon { it.forhåndsvurderinger.lagre(forhåndsvurdering, "{}") }

        assertEquals(listOf(forhåndsvurdering), transaksjon { it.forhåndsvurderinger.finn(FØDSELSNUMMER) })
    }

    @Test
    fun `henter personens forhåndsvurderinger, sist lagret først`() {
        val første = forhåndsvurdering(gyldigFor = 1.januar til 31.januar)
        val andre = forhåndsvurdering(gyldigFor = 1.februar til 28.februar)
        transaksjon { it.forhåndsvurderinger.lagre(første, "{}") }
        transaksjon { it.forhåndsvurderinger.lagre(andre, "{}") }
        transaksjon { it.forhåndsvurderinger.lagre(forhåndsvurdering(fødselsnummer = "01010112345"), "{}") }

        assertEquals(listOf(andre, første), transaksjon { it.forhåndsvurderinger.finn(FØDSELSNUMMER) })
        assertTrue(transaksjon { it.forhåndsvurderinger.finn("31129912345") }.isEmpty())
    }

    private fun forhåndsvurdering(
        fødselsnummer: String = FØDSELSNUMMER,
        gyldigFor: Periode? = 1.januar til 31.januar,
        status: Medlemskapsstatus = Medlemskapsstatus.UavklartMedBrukerspørsmål,
        aarsaker: List<Årsak>? = emptyList(),
    ) = Forhåndsvurdering(
        id = ForhåndsvurderingId.ny(),
        søknadId = UUID.randomUUID(),
        fødselsnummer = fødselsnummer,
        gyldigForSkjæringstidspunkt = gyldigFor,
        status = status,
        aarsaker = aarsaker,
        mottatt = Instant.now().truncatedTo(ChronoUnit.MILLIS),
    )
}
