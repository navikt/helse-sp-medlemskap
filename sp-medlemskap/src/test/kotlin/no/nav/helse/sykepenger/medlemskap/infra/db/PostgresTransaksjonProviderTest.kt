package no.nav.helse.sykepenger.medlemskap.infra.db

import no.nav.helse.februar
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class PostgresTransaksjonProviderTest : DatabaseTest() {
    @Test
    fun `alt arbeidet i transaksjonen lagres når blokken går gjennom`() {
        transaksjon { kontekst ->
            val prøving = nyPrøving()
            kontekst.medlemskapsprøvinger.lagre(prøving)
            kontekst.medlemskapsvurderinger.lagre(prøving.motta(Medlemskapsstatus.Ja))
            kontekst.medlemskapsprøvinger.lagre(prøving)
        }

        assertEquals(1, Database.antallRader("medlemskapsproving"))
        assertEquals(1, Database.antallRader("medlemskapsvurdering"))
    }

    @Test
    fun `ingenting lagres når noe feiler underveis`() {
        assertThrows<RuntimeException> {
            transaksjon { kontekst ->
                val prøving = nyPrøving()
                kontekst.medlemskapsprøvinger.lagre(prøving)
                kontekst.medlemskapsvurderinger.lagre(prøving.motta(Medlemskapsstatus.Ja))
                throw RuntimeException("noe gikk galt etter at arbeidet var gjort")
            }
        }

        assertEquals(0, Database.antallRader("medlemskapsproving"))
        assertEquals(0, Database.antallRader("medlemskapsvurdering"))
    }

    // Feiler vi etter at vurderingen er lagret, men før prøvingen er oppdatert, skal begge deler
    // forsvinne. Ellers ville prøvingen ligget igjen som aktiv med en vurdering som ikke finnes.
    @Test
    fun `delvis fullført prøving rulles tilbake i sin helhet`() {
        val prøving = nyPrøving()
        transaksjon { it.medlemskapsprøvinger.lagre(prøving) }

        assertThrows<RuntimeException> {
            transaksjon { kontekst ->
                kontekst.medlemskapsvurderinger.lagre(prøving.motta(Medlemskapsstatus.Ja))
                kontekst.medlemskapsprøvinger.lagre(prøving)
                throw RuntimeException("krasjer før commit")
            }
        }

        assertEquals(0, Database.antallRader("medlemskapsvurdering"))
        val lagret = transaksjon { it.medlemskapsprøvinger.finnSiste(FØDSELSNUMMER, 1.februar) }
        assertNotNull(lagret)
        assertEquals(false, lagret!!.erAvsluttet) { "Prøvingen skal fortsatt vente på grunnlag" }
    }

    @Test
    fun `skriving er ikke synlig utenfra før transaksjonen er commitet`() {
        transaksjon { kontekst ->
            kontekst.medlemskapsprøvinger.lagre(nyPrøving())

            // Innenfor transaksjonen ser vi vår egen skriving ...
            assertNotNull(kontekst.medlemskapsprøvinger.finnSiste(FØDSELSNUMMER, 1.februar))
            // ... men en annen forbindelse gjør det ikke.
            assertEquals(0, Database.antallRader("medlemskapsproving"))
        }

        assertEquals(1, Database.antallRader("medlemskapsproving"))
    }
}
