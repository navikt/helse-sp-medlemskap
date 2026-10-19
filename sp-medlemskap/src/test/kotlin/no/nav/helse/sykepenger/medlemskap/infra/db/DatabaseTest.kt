package no.nav.helse.sykepenger.medlemskap.infra.db

import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import org.junit.jupiter.api.BeforeEach

internal abstract class DatabaseTest {
    @BeforeEach
    fun tømDatabasen() {
        Database.tøm()
    }

    protected fun <T> transaksjon(block: (Transaksjonskontekst) -> T): T = Database.transaksjonProvider.transaksjon(block)
}
