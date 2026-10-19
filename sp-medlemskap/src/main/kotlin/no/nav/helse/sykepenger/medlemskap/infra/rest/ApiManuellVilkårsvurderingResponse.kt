@file:UseContextualSerialization(UUID::class)

package no.nav.helse.sykepenger.medlemskap.infra.rest

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.util.UUID

@Serializable
internal data class ApiManuellVilkårsvurderingResponse(
    val medlemskapsvurderingId: UUID,
)
