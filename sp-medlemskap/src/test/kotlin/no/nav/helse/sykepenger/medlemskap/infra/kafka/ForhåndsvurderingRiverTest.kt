package no.nav.helse.sykepenger.medlemskap.infra.kafka

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.helse.februar
import no.nav.helse.januar
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Årsak
import no.nav.helse.sykepenger.medlemskap.infra.db.Database
import no.nav.helse.sykepenger.medlemskap.infra.db.DatabaseTest
import no.nav.helse.sykepenger.medlemskap.infra.db.Lagret
import no.nav.helse.til
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ForhåndsvurderingRiverTest : DatabaseTest() {
    private val transaksjon = Database.transaksjonProvider
    private val rapid =
        TestRapid().apply {
            ForhåndsvurderingRiver(this, transaksjon)
        }

    @Test
    fun `lagrer forhåndsvurdering med årsaker`() {
        val søknadId = UUID.randomUUID()

        rapid.sendForhåndsvurdering(
            forhåndsvurderingMelding(
                søknadId = søknadId,
                gyldigFor = 1.januar til 31.januar,
                speilSvar = "UAVKLART_MED_BRUKERSPORSMAAL",
                aarsaker = """[{"regelId":"REGEL_3","forklaringPaaUavklart":"Forklaring"}]""",
            ),
        )

        val lagret = Lagret.forhåndsvurderinger().single()
        assertEquals(søknadId, lagret.søknadId)
        assertEquals(FØDSELSNUMMER, lagret.fødselsnummer)
        assertEquals(Medlemskapsstatus.UavklartMedBrukerspørsmål, lagret.status)
        assertEquals(listOf(Årsak("REGEL_3", "Forklaring")), lagret.aarsaker)
        assertEquals(1.januar til 31.januar, lagret.gyldigForSkjæringstidspunkt)
        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `lagrer forhåndsvurdering uten årsaker`() {
        rapid.sendForhåndsvurdering("""{"soknadId":"${UUID.randomUUID()}","fnr":"$FØDSELSNUMMER","speilSvar":"JA"}""")

        val lagret = Lagret.forhåndsvurderinger().single()
        assertEquals(Medlemskapsstatus.Ja, lagret.status)
        assertNull(lagret.aarsaker)
        assertNull(lagret.gyldigForSkjæringstidspunkt)
    }

    @Test
    fun `ignorerer ugyldige meldinger og meldinger fra rapiden`() {
        rapid.sendForhåndsvurdering(forhåndsvurderingMelding(søknadId = "ikke-en-uuid", gyldigFor = 1.februar til 28.februar))
        rapid.sendForhåndsvurdering(forhåndsvurderingMelding(gyldigFor = 1.februar til 28.februar, speilSvar = "KANSKJE"))
        rapid.sendTestMessage(
            """{"@event_name":"noe","soknadId":"${UUID.randomUUID()}","fnr":"$FØDSELSNUMMER","speilSvar":"JA"}""",
            "nøkkel",
            rapid.rapidName(),
        )

        assertTrue(Lagret.forhåndsvurderinger().isEmpty())
    }

    @Test
    fun `ignorerer forhåndsvurderinger fra topics riveren ikke har valgt`() {
        rapid.sendTestMessage(forhåndsvurderingMelding(gyldigFor = 1.februar til 28.februar), "nøkkel", "annet.topic")

        assertTrue(Lagret.forhåndsvurderinger().isEmpty())
    }

    @Test
    fun `ignorerer meldinger med ugyldig periode`() {
        rapid.sendForhåndsvurdering(melding(""""gyldigForSkjaeringstidspunktFom":"i går","gyldigForSkjaeringstidspunktTom":"2018-02-28""""))
        rapid.sendForhåndsvurdering(melding(""""gyldigForSkjaeringstidspunktFom":"2018-02-01","gyldigForSkjaeringstidspunktTom":"i morgen""""))
        rapid.sendForhåndsvurdering(melding(""""gyldigForSkjaeringstidspunktFom":"2018-02-01""""))
        rapid.sendForhåndsvurdering(melding(""""gyldigForSkjaeringstidspunktTom":"2018-02-28""""))
        rapid.sendForhåndsvurdering(melding(""""gyldigForSkjaeringstidspunktFom":"2018-02-28","gyldigForSkjaeringstidspunktTom":"2018-02-01""""))

        assertTrue(Lagret.forhåndsvurderinger().isEmpty())
    }

    private fun melding(datofelter: String) = """{"soknadId":"${UUID.randomUUID()}","fnr":"$FØDSELSNUMMER","speilSvar":"JA",$datofelter}"""
}
