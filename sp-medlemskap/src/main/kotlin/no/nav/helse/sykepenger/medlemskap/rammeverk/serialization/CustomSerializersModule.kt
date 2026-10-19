package no.nav.helse.sykepenger.medlemskap.rammeverk.serialization

import kotlinx.serialization.modules.SerializersModule
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

val customSerializersModule =
    SerializersModule {
        contextual(BigDecimal::class, BigDecimalStringSerializer)
        contextual(Boolean::class, BooleanStrictSerializer)
        contextual(Instant::class, InstantIsoSerializer)
        contextual(LocalDate::class, LocalDateIsoSerializer)
        contextual(LocalDateTime::class, LocalDateTimeIsoSerializer)
        contextual(UUID::class, UUIDStringSerializer)
    }
