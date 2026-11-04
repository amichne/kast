package io.github.amichne.kast.cli.publicshell

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class PublicCliProjectionContract(
    /** A standalone schema for fixed projection failures; successful projections are dynamic JSON values. */
    @SerialName("failure_schema") val failureSchema: JsonObject,
    @Required val option: String = "--jq",
    @Required val engine: String = "jq >=1.8.2,<2",
    @Required val cardinality: String = "Exactly one JSON value; collect generators explicitly with [FILTER].",
    @Required
    val scope: String = "Only successful documents. Unfiltered output_schema does not describe a user projection.",
    @Required @SerialName("default_timeout_ms") val timeout: Long = PublicJqLimits.DEFAULT_TIMEOUT_MILLIS,
    @Required @SerialName("default_max_bytes") val bytes: Int = PublicJqLimits.DEFAULT_BYTES,
)

@Serializable
internal data class PublicCliFlagDescriptor(
    val name: String,
    @SerialName("takes_value") val takesValue: Boolean,
    val repeated: Boolean,
    /** The packaged JSON Schema fragment is dynamic schema data, not a second semantic contract. */
    val schema: JsonObject? = null,
    @SerialName("value_spellings") val valueSpellings: List<String> = emptyList(),
)
