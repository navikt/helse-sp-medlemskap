package no.nav.helse.sykepenger.medlemskap.infra.db

import no.nav.helse.sykepenger.medlemskap.application.VentendeBehov
import no.nav.helse.sykepenger.medlemskap.domain.Grunnlagsbehov
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsprøving
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class PostgresVentendeBehovRepositoryTest : DatabaseTest() {
    @Test
    fun `lagrer, erstatter og fjerner ventende behov`() {
        val prøving = Medlemskapsprøving.start(FØDSELSNUMMER, OPPSLAGSPERIODE.start, OPPSLAGSPERIODE, Grunnlagsbehov.Forhåndsvurdering)
        val behov =
            VentendeBehov(
                medlemskapsprøvingId = prøving.id,
                fødselsnummer = FØDSELSNUMMER,
                vedtaksperiodeId = UUID.randomUUID().toString(),
                opprinneligBehov = """{"@id":"1"}""",
                frist = Instant.now().truncatedTo(ChronoUnit.MILLIS),
            )
        transaksjon {
            it.medlemskapsprøvinger.lagre(prøving)
            it.ventendeBehov.lagre(behov)
        }

        val lagret = transaksjon { it.ventendeBehov.finn(prøving.id) }!!
        assertEquals(behov.copy(opprinneligBehov = ""), lagret.copy(opprinneligBehov = ""))
        assertEquals(json(behov.opprinneligBehov), json(lagret.opprinneligBehov))
        assertEquals(listOf(prøving.id), transaksjon { it.ventendeBehov.alle(10) })

        val nyttBehov = behov.copy(opprinneligBehov = """{"@id":"2"}""")
        transaksjon { it.ventendeBehov.lagre(nyttBehov) }
        assertEquals(json(nyttBehov.opprinneligBehov), json(transaksjon { it.ventendeBehov.finn(prøving.id) }!!.opprinneligBehov))
        assertEquals(1, Database.antallRader("ventende_behov"))

        transaksjon { it.ventendeBehov.fjern(prøving.id) }
        assertNull(transaksjon { it.ventendeBehov.finn(prøving.id) })
    }

    private fun json(tekst: String) = jacksonObjectMapper().readTree(tekst)
}
