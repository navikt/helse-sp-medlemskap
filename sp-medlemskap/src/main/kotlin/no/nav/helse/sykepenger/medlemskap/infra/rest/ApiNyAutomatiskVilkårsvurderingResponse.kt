@file:UseContextualSerialization(UUID::class)

package no.nav.helse.sykepenger.medlemskap.infra.rest

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.util.UUID

@Serializable
internal data class ApiNyAutomatiskVilkårsvurderingResponse(
    val status: ApiNyAutomatiskVilkårsvurderingStatus,
    val medlemskapsvurderingId: UUID? = null,
)

@Serializable
internal enum class ApiNyAutomatiskVilkårsvurderingStatus {
    VURDERT,
    VENTER_PÅ_GRUNNLAG,
}
