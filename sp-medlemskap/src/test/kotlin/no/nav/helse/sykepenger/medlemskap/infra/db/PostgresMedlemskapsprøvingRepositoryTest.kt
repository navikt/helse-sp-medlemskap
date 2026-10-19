package no.nav.helse.sykepenger.medlemskap.infra.db

import no.nav.helse.februar
import no.nav.helse.januar
import no.nav.helse.sykepenger.medlemskap.domain.Grunnlagsbehov
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsprøving
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.util.PSQLException
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostgresMedlemskapsprøvingRepositoryTest : DatabaseTest() {
    // Prøvingen skal komme tilbake fra databasen med den tilstanden den hadde, slik at en ny pod
    // kan ta over midt i flyten
    @Test
    fun `prøving som venter på grunnlag lagres og hentes tilbake`() {
        val prøving = nyPrøving()
        transaksjon { it.medlemskapsprøvinger.lagre(prøving) }

        val lagret = transaksjon { it.medlemskapsprøvinger.finnSiste(FØDSELSNUMMER, 1.februar) }!!

        assertEquals(prøving.id, lagret.id)
        assertEquals(FØDSELSNUMMER, lagret.fødselsnummer)
        assertEquals(1.februar, lagret.skjæringstidspunkt)
        assertEquals(OPPSLAGSPERIODE, lagret.oppslagsperiode)
        assertEquals(prøving.startet.truncatedTo(ChronoUnit.MILLIS), lagret.startet.truncatedTo(ChronoUnit.MILLIS))
        assertEquals(Grunnlagsbehov.Medlemskap, lagret.uteståendeBehov)
        assertFalse(lagret.erAvsluttet)
    }

    // Samme metode brukes for å skrive en endret prøving: raden oppdateres, det blir ikke en ny
    @Test
    fun `lagring av en fullført prøving oppdaterer raden`() {
        val prøving = nyPrøving()
        transaksjon { it.medlemskapsprøvinger.lagre(prøving) }

        val vurdering =
            transaksjon { kontekst ->
                val vurdering = prøving.motta(Medlemskapsstatus.Ja)
                kontekst.medlemskapsvurderinger.lagre(vurdering)
                kontekst.medlemskapsprøvinger.lagre(prøving)
                vurdering
            }

        val lagret = transaksjon { it.medlemskapsprøvinger.finnSiste(FØDSELSNUMMER, 1.februar) }!!
        assertEquals(1, Database.antallRader("medlemskapsproving"))
        assertEquals(Medlemskapsprøving.Tilstand.Fullført(vurdering.id), lagret.tilstand)
        assertTrue(lagret.erAvsluttet)
        assertNull(lagret.uteståendeBehov)
    }

    @Test
    fun `gjentatt lagring beholder starttidspunktet`() {
        val prøving = nyPrøving()
        transaksjon { it.medlemskapsprøvinger.lagre(prøving) }
        transaksjon { it.medlemskapsprøvinger.lagre(prøving) }

        val lagret = transaksjon { it.medlemskapsprøvinger.finnSiste(FØDSELSNUMMER, 1.februar) }!!
        assertEquals(1, Database.antallRader("medlemskapsproving"))
        assertEquals(prøving.startet.truncatedTo(ChronoUnit.MILLIS), lagret.startet.truncatedTo(ChronoUnit.MILLIS))
    }

    // Invarianten håndheves av det partielle unike indekset, ikke av en sjekk i applikasjonskoden.
    @Test
    fun `tabellen nekter to aktive prøvinger på samme nøkkel`() {
        transaksjon { it.medlemskapsprøvinger.lagre(nyPrøving()) }

        assertThrows<PSQLException> { transaksjon { it.medlemskapsprøvinger.lagre(nyPrøving()) } }

        assertEquals(1, Database.antallRader("medlemskapsproving"))
    }

    @Test
    fun `ny prøving kan startes når den forrige er fullført`() {
        val første = nyPrøving()
        transaksjon { kontekst ->
            kontekst.medlemskapsprøvinger.lagre(første)
            kontekst.medlemskapsvurderinger.lagre(første.motta(Medlemskapsstatus.Ja))
            kontekst.medlemskapsprøvinger.lagre(første)
        }

        val andre = nyPrøving()
        transaksjon { it.medlemskapsprøvinger.lagre(andre) }

        assertEquals(2, Database.antallRader("medlemskapsproving"))
        assertEquals(andre.id, transaksjon { it.medlemskapsprøvinger.finnSiste(FØDSELSNUMMER, 1.februar) }!!.id)
    }

    @Test
    fun `aktive prøvinger på ulike nøkler er tillatt`() {
        transaksjon { kontekst ->
            kontekst.medlemskapsprøvinger.lagre(nyPrøving(skjæringstidspunkt = 1.februar))
            kontekst.medlemskapsprøvinger.lagre(nyPrøving(skjæringstidspunkt = 1.januar))
            kontekst.medlemskapsprøvinger.lagre(nyPrøving(fødselsnummer = ANNET_FØDSELSNUMMER))
        }

        assertEquals(3, Database.antallRader("medlemskapsproving"))
    }

    @Test
    fun `finnSiste gir null når det ikke finnes noen prøving`() {
        assertNull(transaksjon { it.medlemskapsprøvinger.finnSiste(FØDSELSNUMMER, 1.februar) })
    }
}
