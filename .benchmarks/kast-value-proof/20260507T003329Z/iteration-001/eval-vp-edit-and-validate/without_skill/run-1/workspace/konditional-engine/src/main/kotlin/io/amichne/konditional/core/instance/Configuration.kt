@file:OptIn(io.amichne.konditional.api.KonditionalInternalApi::class)

package io.amichne.konditional.core.instance

import io.amichne.konditional.core.FlagDefinition
import io.amichne.konditional.core.features.Feature

data class Configuration(
    val flags: Map<Feature<*, *, *>, FlagDefinition<*, *, *>>,
    val metadata: ConfigurationMetadata = ConfigurationMetadata(),
)

data class ConfigurationMetadata(
    val version: String? = null,
    val generatedAtEpochMillis: Long? = null,
    val source: String? = null,
)
