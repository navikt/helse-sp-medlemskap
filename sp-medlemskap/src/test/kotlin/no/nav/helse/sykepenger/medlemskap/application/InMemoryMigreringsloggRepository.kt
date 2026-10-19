package no.nav.helse.sykepenger.medlemskap.application

internal class InMemoryMigreringsloggRepository : MigreringsloggRepository {
    private val nedlastinger = mutableListOf<Nedlasting>()

    internal val alleNedlastinger: List<Nedlasting> get() = nedlastinger.toList()

    override fun finnesFor(fødselsnummer: String): Boolean = nedlastinger.any { it.fødselsnummer == fødselsnummer }

    override fun lagre(
        fødselsnummer: String,
        antallVurderinger: Int,
        antallHoppetOver: Int,
    ) {
        nedlastinger.add(Nedlasting(fødselsnummer, antallVurderinger, antallHoppetOver))
    }

    internal data class Nedlasting(
        val fødselsnummer: String,
        val antallVurderinger: Int,
        val antallHoppetOver: Int,
    )
}
