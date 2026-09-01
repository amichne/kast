package io.github.amichne.kast.protocol.wire.metadata

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

private const val MAX_SOCKET_DIRECTORY_BYTES = 64
private const val ROOT_DIGEST_BYTES = 12

enum class IdeEndpointSocketDirectoryFailure {
    BLANK,
    TOO_LONG,
    NOT_ABSOLUTE,
    NOT_NORMALIZED,
    CONTAINS_NUL,
}

@JvmInline
value class IdeEndpointSocketDirectory private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<IdeEndpointSocketDirectory,
         * IdeEndpointSocketDirectoryFailure>`.
         *
         * Establishes a bounded absolute normalized POSIX directory that leaves enough room for
         * the deterministic exact-root UDS name. [IdeEndpointSocketDirectoryFailure] is the
         * closed expected failure. Raw text may leave only at CLI configuration or hosted UDS
         * boundaries.
         */
        fun parse(
            raw: String,
        ): Refinement<IdeEndpointSocketDirectory, IdeEndpointSocketDirectoryFailure> = when {
            raw.isBlank() -> Refinement.Rejected(IdeEndpointSocketDirectoryFailure.BLANK)
            raw.toByteArray(StandardCharsets.UTF_8).size > MAX_SOCKET_DIRECTORY_BYTES ->
                Refinement.Rejected(IdeEndpointSocketDirectoryFailure.TOO_LONG)
            '\u0000' in raw ->
                Refinement.Rejected(IdeEndpointSocketDirectoryFailure.CONTAINS_NUL)
            !raw.startsWith('/') ->
                Refinement.Rejected(IdeEndpointSocketDirectoryFailure.NOT_ABSOLUTE)
            raw != "/" && (
                raw.endsWith('/') ||
                    raw.split('/').drop(1).any { it.isEmpty() || it == "." || it == ".." }
            ) -> Refinement.Rejected(IdeEndpointSocketDirectoryFailure.NOT_NORMALIZED)
            else -> Refinement.Refined(IdeEndpointSocketDirectory(raw))
        }
    }
}

@JvmInline
value class IdeEndpointDescriptorPath private constructor(val value: String) {
    companion object {
        internal fun from(socketPath: IdeUnixSocketPath): IdeEndpointDescriptorPath =
            IdeEndpointDescriptorPath("${socketPath.value}.endpoint.json")
    }
}

@JvmInline
value class IdeEndpointStateDirectoryPath private constructor(val value: String) {
    companion object {
        internal fun from(
            directory: IdeEndpointSocketDirectory,
            rootStateDigest: String,
        ): IdeEndpointStateDirectoryPath = IdeEndpointStateDirectoryPath(
            directory.child(".k$rootStateDigest"),
        )
    }
}

@JvmInline
value class IdeEndpointTelemetryDirectoryPath private constructor(val value: String) {
    companion object {
        internal fun from(
            stateDirectory: IdeEndpointStateDirectoryPath,
        ): IdeEndpointTelemetryDirectoryPath = IdeEndpointTelemetryDirectoryPath(
            "${stateDirectory.value}.otel",
        )
    }
}

@JvmInline
value class IdeEndpointTraceFilePath private constructor(val value: String) {
    companion object {
        internal fun from(
            directory: IdeEndpointTelemetryDirectoryPath,
            epoch: IdeRuntimeEpoch,
        ): IdeEndpointTraceFilePath = IdeEndpointTraceFilePath(
            "${directory.value}/traces-${epoch.value}.jsonl",
        )
    }
}

enum class IdeEndpointTelemetryFormat(val identity: String) {
    OTLP_JSON_LINES_V1("otlp-json-lines-v1"),
}

/** Exact private file destination derived from one admitted socket namespace and runtime epoch. */
data class IdeEndpointTelemetryOutput internal constructor(
    val directoryPath: IdeEndpointTelemetryDirectoryPath,
    val traceFilePath: IdeEndpointTraceFilePath,
) : KastTelemetryFileOutput {
    val format: IdeEndpointTelemetryFormat = IdeEndpointTelemetryFormat.OTLP_JSON_LINES_V1
    override val directoryPathText: String get() = directoryPath.value
    override val traceFilePathText: String get() = traceFilePath.value
}

/** One root-exclusive state directory containing its stable UDS and suffix descriptor. */
class IdeEndpointLocation private constructor(
    val canonicalRoot: IdeEndpointCanonicalRoot,
    val stateDirectoryPath: IdeEndpointStateDirectoryPath,
    val socketPath: IdeUnixSocketPath,
    val descriptorPath: IdeEndpointDescriptorPath,
) {
    /** Derives a persistent sibling folder so endpoint retirement can still remove its namespace. */
    fun telemetryOutput(epoch: IdeRuntimeEpoch): IdeEndpointTelemetryOutput {
        val directory = IdeEndpointTelemetryDirectoryPath.from(stateDirectoryPath)
        return IdeEndpointTelemetryOutput(
            directory,
            IdeEndpointTraceFilePath.from(directory, epoch),
        )
    }

    companion object {
        /**
         * Proof transition: `(IdeEndpointSocketDirectory, IdeEndpointCanonicalRoot) ->
         * Refinement<IdeEndpointLocation, IdeEndpointPathFailure>`.
         *
         * Establishes one exact-root exclusive state directory containing its bounded stable UDS
         * and adjacent suffix descriptor. Atomic directory creation serializes cooperating
         * publishers for the root, so staging, publication, and pre-ready rollback operate only
         * under that capability. [IdeEndpointPathFailure] is the closed expected failure. Raw path
         * text may leave only at CLI descriptor-read or hosted publication boundaries.
         */
        fun locate(
            directory: IdeEndpointSocketDirectory,
            root: IdeEndpointCanonicalRoot,
        ): Refinement<IdeEndpointLocation, IdeEndpointPathFailure> {
            val rootDigest = digest(root.value, ROOT_DIGEST_BYTES)
            val stateDirectory = IdeEndpointStateDirectoryPath.from(
                directory,
                rootDigest,
            )
            val rawSocket = "${stateDirectory.value}/s"
            return when (val parsed = IdeUnixSocketPath.parse(rawSocket)) {
                is Refinement.Refined -> Refinement.Refined(
                    IdeEndpointLocation(
                        root,
                        stateDirectory,
                        parsed.value,
                        IdeEndpointDescriptorPath.from(parsed.value),
                    ),
                )
                is Refinement.Rejected -> Refinement.Rejected(parsed.failure)
            }
        }

        private fun digest(value: String, bytes: Int): String = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8)),
            0,
            bytes,
        )
    }
}

private fun IdeEndpointSocketDirectory.child(name: String): String =
    if (value == "/") "/$name" else "$value/$name"
