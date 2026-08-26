package support.plugin

import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
internal data class StandalonePluginReportDocument(
    val schemaVersion: Int,
    val taskId: String,
    val pluginId: String,
    val descriptorJarEntry: String,
    val artifact: PluginArtifactDocument,
    val payloadJars: List<PayloadJarDocument>,
)

@Serializable
internal data class PluginArtifactDocument(
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
)

@Serializable
internal data class PayloadJarDocument(
    val entry: String,
    val sha256: String,
    val sizeBytes: Long,
)

internal enum class StandalonePluginReportFailure {
    MALFORMED_DOCUMENT,
    SCHEMA_VERSION_MISMATCH,
    TASK_ID_MISMATCH,
    PLUGIN_ID_MISMATCH,
    ARTIFACT_PATH_INVALID,
    ARTIFACT_DIGEST_INVALID,
    ARTIFACT_SIZE_INVALID,
    PAYLOAD_EMPTY,
    PAYLOAD_ORDER_MISMATCH,
    PAYLOAD_ENTRY_INVALID,
    PAYLOAD_ENTRY_DUPLICATE,
    PAYLOAD_DIGEST_INVALID,
    PAYLOAD_SIZE_INVALID,
    DESCRIPTOR_ENTRY_MISSING,
    ARTIFACT_UNAVAILABLE,
    ARTIFACT_DIGEST_MISMATCH,
    ARTIFACT_SIZE_MISMATCH,
    ARCHIVE_MALFORMED,
    ARCHIVE_ENTRY_MISMATCH,
    PAYLOAD_DIGEST_MISMATCH,
    PAYLOAD_SIZE_MISMATCH,
    DESCRIPTOR_MISMATCH,
}

internal enum class StandalonePluginReportSchemaVersion(val value: Int) { V1(1) }

internal enum class StandalonePluginReportTaskId(val value: String) { KVP_010("KVP-010") }

@JvmInline
internal value class StandalonePluginDigest private constructor(val value: String) {
    sealed interface Refinement {
        data class Complete(val digest: StandalonePluginDigest) : Refinement
        data object Rejected : Refinement
    }

    companion object {
        /**
         * Proof transition: SHA-256 text `String -> StandalonePluginDigest.Refinement`.
         * Establishes exactly 64 lowercase hexadecimal characters. Expected malformed text is the
         * closed rejected variant; raw digest text is permitted only at report decoding.
         */
        fun refine(raw: String): Refinement = if (SHA256_PATTERN.matches(raw)) {
            Refinement.Complete(StandalonePluginDigest(raw))
        } else {
            Refinement.Rejected
        }

        /**
         * Proof transition: artifact `ByteArray -> StandalonePluginDigest`.
         * Establishes the exact lowercase SHA-256 identity of observed bytes. No expected failure
         * exists; raw bytes are extracted only at the build report or archive boundary.
         */
        fun observe(bytes: ByteArray): StandalonePluginDigest = StandalonePluginDigest(
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            },
        )
    }
}

@JvmInline
internal value class StandalonePluginSizeBytes private constructor(val value: Long) {
    sealed interface Refinement {
        data class Complete(val size: StandalonePluginSizeBytes) : Refinement
        data object Rejected : Refinement
    }

    companion object {
        /**
         * Proof transition: archive size `Long -> StandalonePluginSizeBytes.Refinement`.
         * Establishes a positive size within the closed standalone-plugin archive limit. Expected
         * out-of-range values are rejected; raw sizes remain at report decoding.
         */
        fun artifact(raw: Long): Refinement = if (
            raw in 1..MAX_STANDALONE_PLUGIN_ARCHIVE_BYTES
        ) {
            Refinement.Complete(StandalonePluginSizeBytes(raw))
        } else {
            Refinement.Rejected
        }

        /**
         * Proof transition: payload size `Long -> StandalonePluginSizeBytes.Refinement`.
         * Establishes a positive size within the closed per-JAR payload limit. Expected
         * out-of-range values are rejected; raw sizes remain at report decoding.
         */
        fun payload(raw: Long): Refinement = if (raw in 1..MAX_STANDALONE_PLUGIN_PAYLOAD_BYTES) {
            Refinement.Complete(StandalonePluginSizeBytes(raw))
        } else {
            Refinement.Rejected
        }
    }
}

internal data class StandalonePluginArtifactReference(
    val path: RepositoryRelativeArtifactPath,
    val digest: StandalonePluginDigest,
    val size: StandalonePluginSizeBytes,
)

internal data class StandalonePluginJarReference(
    val entry: PluginArchiveEntry,
    val digest: StandalonePluginDigest,
    val size: StandalonePluginSizeBytes,
)

internal class DecodedStandalonePluginReport internal constructor(
    val pluginId: StandalonePluginId,
    val descriptorJarEntry: PluginArchiveEntry,
    val artifact: StandalonePluginArtifactReference,
    val payloadJars: List<StandalonePluginJarReference>,
) {
    val schemaVersion = StandalonePluginReportSchemaVersion.V1
    val taskId = StandalonePluginReportTaskId.KVP_010
}

internal class VerifiedStandalonePluginReport internal constructor(
    val report: DecodedStandalonePluginReport,
)

internal sealed interface StandalonePluginReportResult {
    data class Complete(val report: DecodedStandalonePluginReport) : StandalonePluginReportResult
    data class Rejected(val failure: StandalonePluginReportFailure) : StandalonePluginReportResult
}

internal sealed interface StandalonePluginArchiveResult {
    data class Complete(val report: VerifiedStandalonePluginReport) : StandalonePluginArchiveResult
    data class Rejected(val failure: StandalonePluginReportFailure) : StandalonePluginArchiveResult
}

private sealed interface PluginArchiveEntryRefinement {
    data class Complete(val entry: PluginArchiveEntry) : PluginArchiveEntryRefinement
    data object Rejected : PluginArchiveEntryRefinement
}

private sealed interface PluginArtifactPathRefinement {
    data class Complete(val path: RepositoryRelativeArtifactPath) : PluginArtifactPathRefinement
    data object Rejected : PluginArtifactPathRefinement
}

private val standalonePluginReportJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    prettyPrint = true
}

/**
 * Proof transition: `StandalonePluginReportDocument -> String`.
 * Preserves the closed KVP-010 build report through its generated serializer. No expected failure
 * exists for an already constructed document; raw JSON leaves only at the Gradle report boundary.
 */
internal fun encodeStandalonePluginReport(document: StandalonePluginReportDocument): String =
    standalonePluginReportJson.encodeToString(StandalonePluginReportDocument.serializer(), document) +
        "\n"

/**
 * Proof transition: report JSON `String -> StandalonePluginReportResult`.
 * Establishes schema and task identity, the canonical plugin identity, one normalized artifact,
 * and a non-empty ordered set of uniquely identified payload JARs with bounded sizes and SHA-256
 * digests. Expected malformed or mismatched evidence is finite [StandalonePluginReportFailure].
 * Raw JSON is permitted only at this report boundary.
 */
internal fun decodeStandalonePluginReport(raw: String): StandalonePluginReportResult {
    val document = try {
        standalonePluginReportJson.decodeFromString(StandalonePluginReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return rejected(StandalonePluginReportFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return rejected(StandalonePluginReportFailure.MALFORMED_DOCUMENT)
    }
    if (document.schemaVersion != StandalonePluginReportSchemaVersion.V1.value) return rejected(
        StandalonePluginReportFailure.SCHEMA_VERSION_MISMATCH,
    )
    if (document.taskId != StandalonePluginReportTaskId.KVP_010.value) return rejected(
        StandalonePluginReportFailure.TASK_ID_MISMATCH,
    )
    if (document.pluginId != KastStandalonePlugin.id.value) return rejected(
        StandalonePluginReportFailure.PLUGIN_ID_MISMATCH,
    )
    val artifactPath = when (val result = refineArtifactPath(document.artifact.path)) {
        is PluginArtifactPathRefinement.Complete -> result.path
        PluginArtifactPathRefinement.Rejected -> return rejected(
            StandalonePluginReportFailure.ARTIFACT_PATH_INVALID,
        )
    }
    val artifactDigest = when (val result = StandalonePluginDigest.refine(document.artifact.sha256)) {
        is StandalonePluginDigest.Refinement.Complete -> result.digest
        StandalonePluginDigest.Refinement.Rejected -> return rejected(
            StandalonePluginReportFailure.ARTIFACT_DIGEST_INVALID,
        )
    }
    val artifactSize = when (val result = StandalonePluginSizeBytes.artifact(
        document.artifact.sizeBytes,
    )) {
        is StandalonePluginSizeBytes.Refinement.Complete -> result.size
        StandalonePluginSizeBytes.Refinement.Rejected -> return rejected(
            StandalonePluginReportFailure.ARTIFACT_SIZE_INVALID,
        )
    }
    if (document.payloadJars.isEmpty()) return rejected(StandalonePluginReportFailure.PAYLOAD_EMPTY)
    if (document.payloadJars.map { it.entry } != document.payloadJars.map { it.entry }.sorted()) {
        return rejected(StandalonePluginReportFailure.PAYLOAD_ORDER_MISMATCH)
    }
    if (document.payloadJars.map { it.entry }.distinct().size != document.payloadJars.size) {
        return rejected(StandalonePluginReportFailure.PAYLOAD_ENTRY_DUPLICATE)
    }
    val jars = buildList {
        document.payloadJars.forEach { jar ->
            val entry = when (val result = refinePayloadEntry(jar.entry)) {
                is PluginArchiveEntryRefinement.Complete -> result.entry
                PluginArchiveEntryRefinement.Rejected -> return rejected(
                    StandalonePluginReportFailure.PAYLOAD_ENTRY_INVALID,
                )
            }
            val digest = when (val result = StandalonePluginDigest.refine(jar.sha256)) {
                is StandalonePluginDigest.Refinement.Complete -> result.digest
                StandalonePluginDigest.Refinement.Rejected -> return rejected(
                    StandalonePluginReportFailure.PAYLOAD_DIGEST_INVALID,
                )
            }
            val size = when (val result = StandalonePluginSizeBytes.payload(jar.sizeBytes)) {
                is StandalonePluginSizeBytes.Refinement.Complete -> result.size
                StandalonePluginSizeBytes.Refinement.Rejected -> return rejected(
                    StandalonePluginReportFailure.PAYLOAD_SIZE_INVALID,
                )
            }
            add(StandalonePluginJarReference(entry, digest, size))
        }
    }
    val descriptor = when (val result = refinePayloadEntry(document.descriptorJarEntry)) {
        is PluginArchiveEntryRefinement.Complete -> result.entry
        PluginArchiveEntryRefinement.Rejected -> return rejected(
            StandalonePluginReportFailure.PAYLOAD_ENTRY_INVALID,
        )
    }
    if (jars.none { it.entry == descriptor }) return rejected(
        StandalonePluginReportFailure.DESCRIPTOR_ENTRY_MISSING,
    )
    return StandalonePluginReportResult.Complete(
        DecodedStandalonePluginReport(
            KastStandalonePlugin.id,
            descriptor,
            StandalonePluginArtifactReference(artifactPath, artifactDigest, artifactSize),
            jars,
        ),
    )
}

/**
 * Proof transition: payload entry text `String -> PluginArchiveEntryRefinement`.
 * Establishes a normalized JAR below the single standalone-plugin `lib` root. Expected malformed
 * or escaping paths are the closed rejected variant; raw text remains at report decoding.
 */
private fun refinePayloadEntry(raw: String): PluginArchiveEntryRefinement = if (
    raw.startsWith("${KastStandalonePlugin.root}/lib/") &&
    raw.endsWith(".jar") &&
    '\\' !in raw &&
    raw.split('/').none { it.isEmpty() || it == "." || it == ".." }
) {
    PluginArchiveEntryRefinement.Complete(PluginArchiveEntry(raw))
} else {
    PluginArchiveEntryRefinement.Rejected
}

/**
 * Proof transition: artifact path text `String -> PluginArtifactPathRefinement`.
 * Establishes the canonical KVP-010 distribution prefix and a normalized repository-relative ZIP
 * path. Expected malformed, absolute, or escaping paths are the closed rejected variant; raw path
 * text remains at report decoding.
 */
private fun refineArtifactPath(raw: String): PluginArtifactPathRefinement {
    if (!raw.startsWith("ide-plugin/build/distributions/kast-ide-plugin-") ||
        raw.startsWith('/') ||
        '\\' in raw ||
        !raw.endsWith(".zip")
    ) return PluginArtifactPathRefinement.Rejected
    if (raw.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
        return PluginArtifactPathRefinement.Rejected
    }
    return try {
        if (Path.of(raw).isAbsolute) {
            PluginArtifactPathRefinement.Rejected
        } else {
            PluginArtifactPathRefinement.Complete(RepositoryRelativeArtifactPath(raw))
        }
    } catch (_: InvalidPathException) {
        PluginArtifactPathRefinement.Rejected
    }
}

private fun rejected(failure: StandalonePluginReportFailure) =
    StandalonePluginReportResult.Rejected(failure)

private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private const val MAX_STANDALONE_PLUGIN_ARCHIVE_BYTES = 80L shl 20
private const val MAX_STANDALONE_PLUGIN_PAYLOAD_BYTES = 32L shl 20
