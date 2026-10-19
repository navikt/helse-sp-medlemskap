package no.nav.helse.sykepenger.medlemskap.rammeverk.rest

import no.nav.helse.sykepenger.medlemskap.rammeverk.auth.Tilgang
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RestBehandlerTest {
    private class HentTingBehandler : GetBehandler<Unit, String, RammeverkFeilkode, Unit> {
        override val påkrevdTilgang = Tilgang.Les
        override val tag = "ting"

        override fun behandle(
            resource: Unit,
            kallKontekst: KallKontekst<Unit>,
        ) = RestResponse.ok("ok")
    }

    @Test
    fun `operationId utledes fra klassenavn med liten forbokstav`() {
        assertEquals("hentTingBehandler", HentTingBehandler().operationIdBasertPåKlassenavn())
    }
}

class RestResponseTest {
    @Test
    fun `ok wrapper body`() {
        val response = RestResponse.ok("hello")
        assertEquals(response, RestResponse.Ok("hello"))
    }

    @Test
    fun `feil wrapper feilkode og detalj`() {
        val response = RestResponse.feil(RammeverkFeilkode.ManglerTilgang, "ingen tilgang")
        assertEquals(response, RestResponse.Feil(RammeverkFeilkode.ManglerTilgang, "ingen tilgang"))
    }
}
