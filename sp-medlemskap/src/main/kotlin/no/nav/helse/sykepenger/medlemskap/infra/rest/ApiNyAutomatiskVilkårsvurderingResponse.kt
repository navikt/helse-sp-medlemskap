@file:UseContextualSerialization(UUID::class)

package no.nav.helse.sykepenger.medlemskap.infra.rest

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.util.UUID

/**
 * Medlemskap vurderes alltid ut fra et nytt oppslag mot medlemskapstjenesten, så den nye vurderingen
 * er aldri klar i svaret. Den meldes til Spleis og Spesialist når svaret er mottatt.
 */
@Serializable
internal data class ApiNyAutomatiskVilkårsvurderingResponse(
    val status: ApiNyAutomatiskVilkårsvurderingStatus,
    val medlemskapsprøvingId: UUID,
)

@Serializable
internal enum class ApiNyAutomatiskVilkårsvurderingStatus {
    VENTER_PÅ_GRUNNLAG,
}
