package no.nav.helse.sykepenger.medlemskap.application

internal interface MigreringsloggRepository {
    fun finnesFor(fødselsnummer: String): Boolean

    /** Hindrer samtidig migrering av samme person ut resten av transaksjonen. */
    fun låsFor(fødselsnummer: String)

    fun lagre(
        fødselsnummer: String,
        antallVurderinger: Int,
        antallHoppetOver: Int,
    )
}
