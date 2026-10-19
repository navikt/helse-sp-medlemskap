package no.nav.helse.sykepenger.medlemskap.application

internal class InMemoryOutbox : Outbox {
    private val meldinger = mutableMapOf<OutboxKonvoluttId, OutboxKonvolutt>()
    private val publiserte = mutableSetOf<OutboxKonvoluttId>()

    internal val upubliserteMeldinger: List<OutboxKonvolutt> get() = meldinger.values.filterNot { it.id in publiserte }

    override fun leggTil(konvolutt: OutboxKonvolutt) {
        meldinger[konvolutt.id] = konvolutt
    }

    override fun hentUpubliserte(maksAntall: Int): List<OutboxKonvolutt> = upubliserteMeldinger.take(maksAntall)

    override fun markerSomSendt(id: OutboxKonvoluttId) {
        publiserte.add(id)
    }
}
