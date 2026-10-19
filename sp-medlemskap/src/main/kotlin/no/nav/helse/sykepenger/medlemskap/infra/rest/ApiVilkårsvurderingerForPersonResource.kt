@file:UseContextualSerialization(UUID::class)

package no.nav.helse.sykepenger.medlemskap.infra.rest

import io.ktor.resources.Resource
import kotlinx.serialization.UseContextualSerialization
import java.util.UUID

@Resource("/api/personer/{personId}/vilkarsvurderinger")
internal class ApiVilkårsvurderingerForPersonResource(
    val personId: String,
    val medlemskapsvurderingId: UUID,
)
