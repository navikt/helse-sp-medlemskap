package no.nav.helse.sykepenger.medlemskap.rammeverk.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Sikrer deny-by-default og korrekt mapping fra Entra-gruppe-UUID-er til [Tilgang] FØR koden tas i
 * bruk mot ekte Entra-grupper.
 */
class TilgangsgrupperTilTilgangerTest {
    private val sut = TilgangsgrupperTilTilganger(tilgangLesGruppeIder = setOf("les-uuid"), tilgangSkrivGruppeIder = setOf("skriv-uuid"))

    @Test
    fun `gruppe-uuid som matcher TILGANG_SKRIV gir baade Skriv og Les`() {
        assertEquals(setOf(Tilgang.Skriv, Tilgang.Les), sut.tilganger(setOf("skriv-uuid")))
    }

    @Test
    fun `gruppe-uuid som matcher TILGANG_LES gir kun Les`() {
        assertEquals(setOf(Tilgang.Les), sut.tilganger(setOf("les-uuid")))
    }

    @Test
    fun `ukjent gruppe-uuid gir tomt tilgangssett, ikke feil (deny-by-default)`() {
        assertEquals(emptySet<Tilgang>(), sut.tilganger(setOf("ukjent-uuid")))
    }

    @Test
    fun `tomt gruppesett gir tomt tilgangssett`() {
        assertEquals(emptySet<Tilgang>(), sut.tilganger(emptySet()))
    }

    @Test
    fun `saksbehandler i baade les- og skriv-gruppe faar begge tilganger`() {
        assertEquals(setOf(Tilgang.Les, Tilgang.Skriv), sut.tilganger(setOf("les-uuid", "skriv-uuid")))
    }

    @Test
    fun `flere les-grupper stottes - medlem av en av dem gir Les`() {
        val flereLesGrupper =
            TilgangsgrupperTilTilganger(
                tilgangLesGruppeIder = setOf("les-uuid-1", "les-uuid-2", "les-uuid-3"),
                tilgangSkrivGruppeIder = setOf("skriv-uuid"),
            )
        assertEquals(setOf(Tilgang.Les), flereLesGrupper.tilganger(setOf("les-uuid-2")))
    }

    @Test
    fun `fraEnv splitter kommaseparert TILGANG_LES i flere gruppe-uuid-er`() {
        val fraEnv =
            TilgangsgrupperTilTilganger.fraEnv(
                mapOf(
                    "TILGANG_LES" to "les-uuid-1, les-uuid-2 ,les-uuid-3",
                    "TILGANG_SKRIV" to "skriv-uuid",
                ),
            )
        assertEquals(setOf(Tilgang.Les), fraEnv.tilganger(setOf("les-uuid-2")))
        assertEquals(emptySet<Tilgang>(), fraEnv.tilganger(setOf("ukjent-uuid")))
    }
}
