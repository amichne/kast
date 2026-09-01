package io.github.amichne.kast.protocol.wire.metadata

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.InvalidPathException
import java.nio.file.Path

enum class SidecarTelemetryOutputFailure {
    BLANK,
    NOT_ABSOLUTE,
    NOT_NORMALIZED,
    CONTAINS_NUL,
    MISSING_FILE_NAME,
}

@JvmInline
value class SidecarTelemetryDirectoryPath internal constructor(val value: String)

@JvmInline
value class SidecarTraceFilePath internal constructor(val value: String)

enum class SidecarTelemetryFormat(val identity: String) {
    OTLP_JSON_LINES_V1("otlp-json-lines-v1"),
}

/** Host-neutral exact file destination consumed by the OpenTelemetry adapter. */
interface KastTelemetryFileOutput {
    val directoryPathText: String
    val traceFilePathText: String
}

/** Exact persistent telemetry destination derived only from one admitted sidecar socket. */
data class SidecarTelemetryOutput private constructor(
    val directoryPath: SidecarTelemetryDirectoryPath,
    val traceFilePath: SidecarTraceFilePath,
) : KastTelemetryFileOutput {
    val format: SidecarTelemetryFormat = SidecarTelemetryFormat.OTLP_JSON_LINES_V1
    override val directoryPathText: String get() = directoryPath.value
    override val traceFilePathText: String get() = traceFilePath.value

    companion object {
        /**
         * Proof transition: `String -> Refinement<SidecarTelemetryOutput,
         * SidecarTelemetryOutputFailure>`.
         *
         * Establishes one absolute normalized per-socket destination inside the sidecar's durable
         * state directory. Expected path rejection remains closed data. Raw text leaves only at
         * the CLI/indexer socket boundary and the telemetry filesystem adapter.
         */
        fun fromSocketPath(
            rawSocketPath: String,
        ): Refinement<SidecarTelemetryOutput, SidecarTelemetryOutputFailure> {
            if (rawSocketPath.isBlank()) {
                return Refinement.Rejected(SidecarTelemetryOutputFailure.BLANK)
            }
            if ('\u0000' in rawSocketPath) {
                return Refinement.Rejected(SidecarTelemetryOutputFailure.CONTAINS_NUL)
            }
            val socketPath = try {
                Path.of(rawSocketPath)
            } catch (_: InvalidPathException) {
                return Refinement.Rejected(SidecarTelemetryOutputFailure.NOT_NORMALIZED)
            }
            if (!socketPath.isAbsolute) {
                return Refinement.Rejected(SidecarTelemetryOutputFailure.NOT_ABSOLUTE)
            }
            if (socketPath.normalize() != socketPath) {
                return Refinement.Rejected(SidecarTelemetryOutputFailure.NOT_NORMALIZED)
            }
            val fileName = socketPath.fileName
                ?: return Refinement.Rejected(SidecarTelemetryOutputFailure.MISSING_FILE_NAME)
            val stateDirectory = socketPath.resolveSibling("$fileName.state")
            val telemetryDirectory = stateDirectory.resolve("otel")
            return Refinement.Refined(
                SidecarTelemetryOutput(
                    SidecarTelemetryDirectoryPath(telemetryDirectory.toString()),
                    SidecarTraceFilePath(telemetryDirectory.resolve("traces.jsonl").toString()),
                ),
            )
        }
    }
}
