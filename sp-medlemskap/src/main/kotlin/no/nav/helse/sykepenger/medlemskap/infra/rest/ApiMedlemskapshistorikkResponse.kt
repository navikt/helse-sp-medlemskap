@file:UseContextualSerialization(Instant::class, LocalDate::class)

package no.nav.helse.sykepenger.medlemskap.infra.rest

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.Instant
import java.time.LocalDate

@Serializable
internal data class ApiMedlemskapshistorikkResponse(
    val skjæringstidspunkt: LocalDate,
    val historikk: List<ApiMedlemskapshistorikkInnslag>,
)

@Serializable
internal data class ApiMedlemskapshistorikkInnslag(
    val vurdertTidspunkt: Instant,
    val medlemskapsvurdering: ApiMedlemskapsvurdering,
)
