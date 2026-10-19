package no.nav.helse.sykepenger.medlemskap.infra.db

import no.nav.helse.februar
import no.nav.helse.januar
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidssituasjon
import no.nav.helse.sykepenger.medlemskap.domain.Grunnlagsbehov
import no.nav.helse.sykepenger.medlemskap.domain.Kategori
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsprøving
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.util.PSQLException
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal class PostgresMedlemskapsprøvingRepositoryTest : DatabaseTest() {
    // Prøvingen skal komme tilbake fra databasen med den tilstanden den hadde, slik at en ny pod
    // kan ta over midt i flyten
    @Test
    fun `prøving som venter på grunnlag lagres og hentes tilbake`() {
        val prøving = Medlemskapsprøving.start(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker).prøving
        transaksjon { it.medlemskapsprøvinger.lagre(prøving) }

        val lagret = transaksjon { it.medlemskapsprøvinger.finnSiste(FØDSELSNUMMER, 1.februar, Kategori.Arbeidstaker) }!!

        assertEquals(prøving.id, lagret.id)
        assertEquals(FØDSELSNUMMER, lagret.fødselsnummer)
        assertEquals(1.februar, lagret.skjæringstidspunkt)
        assertEquals(Kategori.Arbeidstaker, lagret.kategori)
        assertEquals(prøving.startet.truncatedTo(ChronoUnit.MILLIS), lagret.startet.truncatedTo(ChronoUnit.MILLIS))
        assertEquals(Grunnlagsbehov.Arbeidsforhold, lagret.uteståendeBehov)
        assertFalse(lagret.erAvsluttet)
    }

    @Test
    fun `prøving i tilstand startet lagres og hentes tilbake`() {
        val prøving = prøvingFraLagring(Medlemskapsprøving.Tilstand.Startet)
        transaksjon { it.medlemskapsprøvinger.lagre(prøving) }

        val lagret = transaksjon { it.medlemskapsprøvinger.finnSiste(FØDSELSNUMMER, 1.februar, Kategori.Arbeidstaker) }!!

        assertEquals(Medlemskapsprøving.Tilstand.Startet, lagret.tilstand)
        assertNull(lagret.uteståendeBehov)
        assertFalse(lagret.erAvsluttet)
    }

    // En prøving som fullføres uten innhenting skrives med vurderingen sin med en gang
    @Test
    fun `fullført prøving lagres med vurderingen`() {
        val påbegynt = Medlemskapsprøving.start(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.SelvstendigNæringsdrivende)
        transaksjon {
            it.medlemskapsprøvinger.lagre(påbegynt.prøving)
            it.medlemskapsvurderinger.lagre(påbegynt.vurdering!!)
        }

        val lagret = transaksjon { it.medlemskapsprøvinger.finnSiste(FØDSELSNUMMER, 1.februar, Kategori.SelvstendigNæringsdrivende) }!!

        assertTrue(lagret.erAvsluttet)
        assertEquals(Medlemskapsprøving.Tilstand.Fullført(påbegynt.vurdering!!.id), lagret.tilstand)
    }

    // Samme metode brukes for å skrive en endret prøving: raden oppdateres, det blir ikke en ny
    @Test
    fun `lagring av en endret prøving oppdaterer raden`() {
        val prøving = Medlemskapsprøving.start(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker).prøving
        transaksjon { it.medlemskapsprøvinger.lagre(prøving) }

        val vurdering =
            transaksjon { kontekst ->
                val vurdering = prøving.motta(arbeidstakergrunnlag())
                kontekst.medlemskapsvurderinger.lagre(vurdering)
                kontekst.medlemskapsprøvinger.lagre(prøving)
                vurdering
            }

        val lagret = transaksjon { it.medlemskapsprøvinger.finnSiste(FØDSELSNUMMER, 1.februar, Kategori.Arbeidstaker) }!!
        assertEquals(1, Database.antallRader("medlemskapsproving"))
        assertEquals(Medlemskapsprøving.Tilstand.Fullført(vurdering.id), lagret.tilstand)
        assertNull(lagret.uteståendeBehov)
    }

    // Feltene som ligger fast fra prøvingen startet skal ikke skrives om ved en oppdatering
    @Test
    fun `gjentatt lagring beholder starttidspunktet`() {
        val prøving = Medlemskapsprøving.start(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker).prøving
        transaksjon { it.medlemskapsprøvinger.lagre(prøving) }
        transaksjon { it.medlemskapsprøvinger.lagre(prøving) }

        val lagret = transaksjon { it.medlemskapsprøvinger.finnSiste(FØDSELSNUMMER, 1.februar, Kategori.Arbeidstaker) }!!
        assertEquals(1, Database.antallRader("medlemskapsproving"))
        assertEquals(prøving.startet.truncatedTo(ChronoUnit.MILLIS), lagret.startet.truncatedTo(ChronoUnit.MILLIS))
    }

    // Invarianten håndheves av det partielle unike indekset, ikke av en sjekk i applikasjonskoden.
    // `on conflict (id)` fanger bare opp den samme prøvingen på nytt, ikke en ny prøving på samme nøkkel.
    @Test
    fun `tabellen nekter to aktive prøvinger på samme nøkkel`() {
        transaksjon { it.medlemskapsprøvinger.lagre(nyPrøving()) }

        assertThrows<PSQLException> { transaksjon { it.medlemskapsprøvinger.lagre(nyPrøving()) } }

        assertEquals(1, Database.antallRader("medlemskapsproving"))
    }

    // ... men en fullført prøving er ikke aktiv, så en ny prøving kan startes etterpå
    @Test
    fun `ny prøving kan startes når den forrige er fullført`() {
        val første = nyPrøving()
        transaksjon { kontekst ->
            kontekst.medlemskapsprøvinger.lagre(første)
            kontekst.medlemskapsvurderinger.lagre(første.motta(arbeidstakergrunnlag()))
            kontekst.medlemskapsprøvinger.lagre(første)
        }

        val andre = nyPrøving()
        transaksjon { it.medlemskapsprøvinger.lagre(andre) }

        assertEquals(2, Database.antallRader("medlemskapsproving"))
        assertEquals(andre.id, transaksjon { it.medlemskapsprøvinger.finnSiste(FØDSELSNUMMER, 1.februar, Kategori.Arbeidstaker) }!!.id)
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
        assertNull(transaksjon { it.medlemskapsprøvinger.finnSiste(FØDSELSNUMMER, 1.februar, Kategori.Arbeidstaker) })
    }

    private fun nyPrøving(
        fødselsnummer: String = FØDSELSNUMMER,
        skjæringstidspunkt: LocalDate = 1.februar,
    ) = Medlemskapsprøving.start(fødselsnummer, skjæringstidspunkt, Arbeidssituasjon.Arbeidstaker).prøving

    private fun prøvingFraLagring(tilstand: Medlemskapsprøving.Tilstand) =
        Medlemskapsprøving.fraLagring(
            id = MedlemskapsprøvingId.ny(),
            fødselsnummer = FØDSELSNUMMER,
            skjæringstidspunkt = 1.februar,
            kategori = Kategori.Arbeidstaker,
            startet = Instant.now(),
            tilstand = tilstand,
        )

    private companion object {
        const val ANNET_FØDSELSNUMMER = "12029240046"
    }
}
