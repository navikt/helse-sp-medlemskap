package no.nav.helse.sykepenger.medlemskap.rammeverk.testfixtures

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoIdProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** In-memory [PersonPseudoIdProvider] til bruk i tester — ingen ekte Valkey-avhengighet. */
class InMemoryPersonPseudoIdProvider : PersonPseudoIdProvider {
    private val lagring = ConcurrentHashMap<PersonPseudoId, Identitetsnummer>()

    override fun nyPersonPseudoId(identitetsnummer: Identitetsnummer): PersonPseudoId {
        val id = PersonPseudoId(UUID.randomUUID())
        lagring[id] = identitetsnummer
        return id
    }

    override fun finnIdentitetsnummer(personPseudoId: PersonPseudoId): Identitetsnummer? = lagring[personPseudoId]

    /** Fjerner en pseudo-id, for å simulere utløpt TTL i tester. */
    fun fjern(personPseudoId: PersonPseudoId) {
        lagring.remove(personPseudoId)
    }
}
