@file:UseContextualSerialization(LocalDate::class)

package no.nav.helse.sykepenger.medlemskap.infra.rest

import io.ktor.resources.Resource
import kotlinx.serialization.UseContextualSerialization
import java.time.LocalDate

@Resource("/api/personer/{personId}/medlemskapsvurderinger/historikk")
internal class ApiMedlemskapshistorikkResource(
    val personId: String,
    val skjæringstidspunkt: LocalDate,
)
