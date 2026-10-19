package no.nav.helse.sykepenger.medlemskap.application

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import java.time.Instant
import java.time.LocalDate
import java.util.*

@JvmInline
internal value class OutboxKonvoluttId(
    val value: UUID,
) {
    override fun toString() = value.toString()

    companion object {
        fun ny() = OutboxKonvoluttId(UUID.randomUUID())
    }
}

internal data class OutboxKonvolutt(
    val id: OutboxKonvoluttId,
    val melding: OutboxMelding,
    val identitetsnummer: Identitetsnummer,
) {
    companion object {
        fun ny(
            melding: OutboxMelding,
            identitetsnummer: Identitetsnummer,
        ) = OutboxKonvolutt(
            id = OutboxKonvoluttId.ny(),
            melding = melding,
            identitetsnummer = identitetsnummer,
        )
    }
}

internal sealed interface OutboxMelding {
    data class MedlemskapsvurderingEndret(
        val skjæringstidspunkt: LocalDate,
        val medlemskapsvurderingId: UUID,
        val manuellVurdering: Boolean,
    ) : OutboxMelding

    data class Saksbehandlerhandling(
        val skjæringstidspunkt: LocalDate,
        val saksbehandlerIdent: String,
        val handlingId: UUID,
        val handlingType: HandlingType,
        val kreverTotrinnsvurdering: Boolean,
        val tidspunkt: Instant,
    ) : OutboxMelding
}

enum class HandlingType {
    MANUELT_VURDERT_MEDLEMSKAP,
    NY_AUTOMATISK_MEDLEMSKAPSVURDERING,
}

internal interface Outbox {
    fun leggTil(konvolutt: OutboxKonvolutt)

    fun hentUpubliserte(maksAntall: Int = 50): List<OutboxKonvolutt>

    fun markerSomSendt(id: OutboxKonvoluttId)
}
