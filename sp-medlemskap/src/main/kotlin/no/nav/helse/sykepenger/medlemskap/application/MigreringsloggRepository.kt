package no.nav.helse.sykepenger.medlemskap.application

internal interface MigreringsloggRepository {
    fun finnesFor(fødselsnummer: String): Boolean

    fun lagre(
        fødselsnummer: String,
        antallVurderinger: Int,
        antallHoppetOver: Int,
    )
}
