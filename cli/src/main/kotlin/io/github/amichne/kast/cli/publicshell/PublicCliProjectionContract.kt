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
    @Required val engine: String = "jackson-jq $embeddedJqVersion (embedded; jq 1.7 compatibility, not full jq parity)",
    @Required
    val preflight: String = "Syntax and numeric literals before workspace effects; function names at evaluation.",
    @Required
    val limitations: String =
        "No input/inputs, halt_error, environment, external modules or string interpolation. " +
            "Arithmetic uses signed 64-bit integers (including overflow) and IEEE doubles; codecs reject value loss.",
    @Required val cardinality: String = "Exactly one JSON value; collect generators explicitly with [FILTER].",
    @Required
    val scope: String = "Only successful documents. Unfiltered output_schema does not describe a user projection.",
    @Required @SerialName("default_timeout_ms") val timeout: Long = PublicJqLimits.DEFAULT_TIMEOUT_MILLIS,
    @Required @SerialName("default_max_bytes") val bytes: Int = PublicJqLimits.DEFAULT_BYTES,
)

internal val embeddedJqVersion: String by lazy {
    val properties = java.util.Properties()
    checkNotNull(PublicJqEngine::class.java.getResourceAsStream("/jq-engine.properties")).use(properties::load)
    checkNotNull(properties.getProperty("version"))
}

@Serializable
internal data class PublicCliFlagDescriptor(
    val name: String,
    @SerialName("takes_value") val takesValue: Boolean,
    val repeated: Boolean,
    /** The packaged JSON Schema fragment is dynamic schema data, not a second semantic contract. */
    val schema: JsonObject? = null,
    @SerialName("value_spellings") val valueSpellings: List<String> = emptyList(),
)
