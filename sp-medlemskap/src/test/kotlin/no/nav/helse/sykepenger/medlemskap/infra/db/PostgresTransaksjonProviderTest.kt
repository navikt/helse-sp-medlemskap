package no.nav.helse.sykepenger.medlemskap.infra.db

import no.nav.helse.februar
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidssituasjon
import no.nav.helse.sykepenger.medlemskap.domain.Kategori
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsprøving
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class PostgresTransaksjonProviderTest : DatabaseTest() {
    @Test
    fun `alt arbeidet i transaksjonen lagres når blokken går gjennom`() {
        transaksjon { kontekst ->
            val påbegynt = Medlemskapsprøving.start(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.SelvstendigNæringsdrivende)
            kontekst.medlemskapsprøvinger.lagre(påbegynt.prøving)
            kontekst.medlemskapsvurderinger.lagre(checkNotNull(påbegynt.vurdering))
        }

        assertEquals(1, Database.antallRader("medlemskapsproving"))
        assertEquals(1, Database.antallRader("medlemskapsvurdering"))
        assertEquals(1, Database.antallRader("vilkarsvurdering"))
    }

    @Test
    fun `ingenting lagres når noe feiler underveis`() {
        assertThrows<RuntimeException> {
            transaksjon { kontekst ->
                val påbegynt = Medlemskapsprøving.start(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.SelvstendigNæringsdrivende)
                kontekst.medlemskapsprøvinger.lagre(påbegynt.prøving)
                kontekst.medlemskapsvurderinger.lagre(checkNotNull(påbegynt.vurdering))
                throw RuntimeException("noe gikk galt etter at arbeidet var gjort")
            }
        }

        assertEquals(0, Database.antallRader("medlemskapsproving"))
        assertEquals(0, Database.antallRader("medlemskapsvurdering"))
        assertEquals(0, Database.antallRader("vilkarsvurdering"))
    }

    // Feiler vi etter at vurderingen er lagret, men før prøvingen er oppdatert, skal begge deler
    // forsvinne. Ellers ville prøvingen ligget igjen som aktiv med en vurdering som ikke finnes.
    @Test
    fun `delvis fullført prøving rulles tilbake i sin helhet`() {
        val prøving = Medlemskapsprøving.start(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.Arbeidstaker).prøving
        transaksjon { it.medlemskapsprøvinger.lagre(prøving) }

        assertThrows<RuntimeException> {
            transaksjon { kontekst ->
                kontekst.medlemskapsvurderinger.lagre(prøving.motta(arbeidstakergrunnlag()))
                kontekst.medlemskapsprøvinger.lagre(prøving)
                throw RuntimeException("krasjer før commit")
            }
        }

        assertEquals(0, Database.antallRader("medlemskapsvurdering"))
        assertEquals(0, Database.antallRader("vilkarsvurdering"))
        val lagret = transaksjon { it.medlemskapsprøvinger.finnSiste(FØDSELSNUMMER, 1.februar, Kategori.Arbeidstaker) }
        assertNotNull(lagret)
        assertEquals(false, lagret!!.erAvsluttet) { "Prøvingen skal fortsatt vente på grunnlag" }
    }

    @Test
    fun `skriving er ikke synlig utenfra før transaksjonen er commitet`() {
        transaksjon { kontekst ->
            val påbegynt = Medlemskapsprøving.start(FØDSELSNUMMER, 1.februar, Arbeidssituasjon.SelvstendigNæringsdrivende)
            kontekst.medlemskapsprøvinger.lagre(påbegynt.prøving)

            // Innenfor transaksjonen ser vi vår egen skriving ...
            assertNotNull(kontekst.medlemskapsprøvinger.finnSiste(FØDSELSNUMMER, 1.februar, Kategori.SelvstendigNæringsdrivende))
            // ... men en annen forbindelse gjør det ikke.
            assertEquals(0, Database.antallRader("medlemskapsproving"))
        }

        assertEquals(1, Database.antallRader("medlemskapsproving"))
    }
}
