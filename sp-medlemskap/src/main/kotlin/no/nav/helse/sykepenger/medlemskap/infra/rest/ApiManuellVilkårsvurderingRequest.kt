@file:UseContextualSerialization(LocalDate::class)

package no.nav.helse.sykepenger.medlemskap.infra.rest

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.LocalDate

@Serializable
internal data class ApiManuellVilkårsvurderingRequest(
    val skjæringstidspunkt: LocalDate,
    val status: ApiManuellMedlemskapsstatus,
    val fritekstbegrunnelse: String,
    val journalpostId: List<String>,
)

/** En saksbehandler konkluderer alltid; uavklart er bare et mulig svar fra medlemskapstjenesten. */
@Serializable
internal enum class ApiManuellMedlemskapsstatus {
    JA,
    NEI,
}
