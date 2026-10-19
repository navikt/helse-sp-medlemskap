package no.nav.helse.sykepenger.medlemskap.infra.kafka

import tools.jackson.databind.JsonNode
import java.util.UUID

internal fun JsonNode.asUUID(): UUID = UUID.fromString(this.asString())
