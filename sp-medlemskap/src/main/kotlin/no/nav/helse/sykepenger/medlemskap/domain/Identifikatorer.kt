package no.nav.helse.sykepenger.medlemskap.domain

import java.util.UUID

@JvmInline
internal value class MedlemskapsprøvingId(
    val value: UUID,
) {
    override fun toString() = value.toString()

    companion object {
        fun ny() = MedlemskapsprøvingId(UUID.randomUUID())
    }
}

@JvmInline
internal value class MedlemskapsvurderingId(
    val value: UUID,
) {
    override fun toString() = value.toString()

    companion object {
        fun ny() = MedlemskapsvurderingId(UUID.randomUUID())
    }
}
