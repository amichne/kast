package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.distribution.contract.configuration.CliOutputFailure
import io.github.amichne.kast.protocol.wire.presentation.QueryCompletionPublicReadQuery
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class PublicJqStage {
    COMPILE,
    FILTER,
    PRESENTATION,
}

@Serializable
internal enum class PublicJqFailure(val exit: PublicShellExit, val guidance: String) {
    STDERR_LIMIT(
        PublicShellExit.IO,
        "jq stderr exceeded the ${PublicJqLimits.STDERR_BYTES}-byte diagnostic limit. " +
            "Reduce diagnostic output; raw stderr is not published.",
    ),
    OUTPUT_VALUE_LOSS(
        PublicShellExit.PROTOCOL,
        "TOON cannot preserve this projection exactly. Use --json to preserve jq's JSON values.",
    ),
    OUTPUT_ENCODING_REJECTED(
        PublicShellExit.PROTOCOL,
        "The projection cannot be encoded in this format. Use --json; TOON projection depth is limited to 128.",
    ),
    NUMERIC_VALUE_LOSS(
        PublicShellExit.PROTOCOL,
        "Embedded jq's numeric conversion would change a value. Keep exact identifiers as strings or use --json | jq.",
    ),
    JSON_LIMIT(
        PublicShellExit.PROTOCOL,
        "Embedded JSON exceeds the ${PublicJqLimits.JSON_NESTING} nesting or " +
            "${PublicJqLimits.JSON_NUMBER_CHARACTERS} numeric-token character limit. Reduce the value's size or depth.",
    ),
    RESOURCE_LIMIT(
        PublicShellExit.IO,
        "Embedded jq exceeded its private 512 MiB heap or stack. Reduce the projection's working set or recursion.",
    ),
    WORKER_FAILED(
        PublicShellExit.RUNTIME,
        "The bundled jq worker terminated unexpectedly. Its bounded child_exit is retained; raw stderr is private.",
    ),
    PROGRAM_UNSUPPORTED(
        PublicShellExit.INPUT,
        "Embedded jq does not admit string interpolation. Use string concatenation or --json | jq.",
    ),
    INVALID_OUTPUT_FORMAT(PublicShellExit.INPUT, "Select JSON with --json or a valid KAST_OUTPUT_TOON setting."),
    ENGINE_UNAVAILABLE(
        PublicShellExit.RUNTIME,
        "The bundled jq worker could not start. Verify the Kast Control installation; no external jq is required.",
    ),
    PROGRAM_INVALID(
        PublicShellExit.INPUT,
        "Embedded jq rejected the syntax. Builtin names and input types are checked during evaluation.",
    ),
    EVALUATION_FAILED(
        PublicShellExit.INPUT,
        "jq could not evaluate the program on this document. Check its paths and types.",
    ),
    OUTPUT_CARDINALITY(
        PublicShellExit.INPUT,
        "--jq requires exactly one JSON value. Collect a generator with [FILTER]. Use --json | jq for streams.",
    ),
    OUTPUT_INVALID(PublicShellExit.PROTOCOL, "jq did not return one valid UTF-8 JSON value."),
    BYTE_LIMIT(
        PublicShellExit.IO,
        "jq input or output exceeded --jq-max-bytes; select a larger explicit limit or a smaller projection.",
    ),
    TIME_LIMIT(
        PublicShellExit.ELAPSED,
        "jq exceeded --jq-timeout-ms. This deadline covers filtering, not workspace preparation.",
    ),
    PROCESS_IO(PublicShellExit.IO, "jq process I/O failed; semantic work is not retried."),
}

@Serializable
internal data class PublicJqRejection(
    val failure: PublicJqFailure,
    val stage: PublicJqStage,
    @SerialName("timeout_ms") val timeoutMillis: Long,
    @SerialName("max_bytes") val maxBytes: Int,
    @SerialName("child_exit") val childExit: Int? = null,
    @SerialName("engine_version") val engineVersion: String? = null,
    val recovery: QueryCompletionPublicReadQuery? = null,
    @Required val guidance: String = failure.guidance,
    @Required val type: String = "jq_rejected",
    @Required @SerialName("stderr_max_bytes") val stderrMaxBytes: Int = PublicJqLimits.STDERR_BYTES,
) {
    fun prepared(): PublicPreparedOutput = PublicPreparedOutput(Json.encodeToString(this), failure.exit)
}

internal fun jqPresentationFailure(
    failure: CliOutputFailure,
    projection: PublicOutputContent.Projection,
): PublicPreparedOutput {
    val mapped =
        when (failure) {
            CliOutputFailure.OUTPUT_VALUE_LOSS -> PublicJqFailure.OUTPUT_VALUE_LOSS
            CliOutputFailure.OUTPUT_ENCODING_REJECTED -> PublicJqFailure.OUTPUT_ENCODING_REJECTED
            CliOutputFailure.OUTPUT_LIMIT_EXCEEDED -> PublicJqFailure.BYTE_LIMIT
            CliOutputFailure.INVALID_OUTPUT_FORMAT -> PublicJqFailure.INVALID_OUTPUT_FORMAT
        }
    return jqRejection(mapped, PublicJqStage.PRESENTATION, projection.limits)
        .copy(recovery = projection.recovery)
        .prepared()
}

internal fun jqRejection(
    failure: PublicJqFailure,
    stage: PublicJqStage,
    limits: PublicJqLimits,
    childExit: Int? = null,
    version: String? = "jackson-jq $embeddedJqVersion",
): PublicJqRejection = PublicJqRejection(failure, stage, limits.timeoutMillis, limits.bytes, childExit, version)
