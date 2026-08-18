package io.github.amichne.kast.distribution.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val MANIFEST_SCHEMA_VERSION = 1

/** Closed expected failures for semantic-runtime realization and admission. */
enum class SemanticRuntimeFailure(val reason: String) {
    MANIFEST_INVALID("manifest-invalid"),
    SOURCE_INVALID("source-invalid"),
    ARTIFACT_UNAVAILABLE("artifact-unavailable"),
    DIGEST_MISMATCH("digest-mismatch"),
    ARCHIVE_REJECTED("archive-rejected"),
    LAYOUT_INVALID("layout-invalid"),
    RUNTIME_INCOMPATIBLE("runtime-incompatible"),
    PROCESS_START_FAILED("process-start-failed"),
    ENDPOINT_UNAVAILABLE("endpoint-unavailable"),
    RUNTIME_IDENTITY_MISMATCH("runtime-identity-mismatch"),
    INTERRUPTED("interrupted"),
}

class SemanticRuntimeArchive internal constructor(
    val fileName: RuntimeLayoutEntry,
    val url: URI,
    val digest: RuntimeDigest,
    val size: RuntimeArchiveSize,
)

class SemanticRuntimeLayout internal constructor(
    val executable: RuntimeLayoutEntry,
    val requiredEntries: List<RuntimeLayoutEntry>,
    val executableEntries: List<RuntimeLayoutEntry>,
)

/** One completely admitted, canonical semantic-runtime requirement. */
class SemanticRuntimeManifest internal constructor(
    val runtimeId: SemanticRuntimeId,
    val productVersion: RuntimeProductVersion,
    val platform: RuntimePlatform,
    val architecture: RuntimeArchitecture,
    val ideaBuild: IntellijBuildIdentity,
    val kotlinPluginBuild: KotlinPluginBuildIdentity,
    val kastPluginDigest: RuntimeDigest,
    val wireSchemaId: RuntimeWireSchemaIdentity,
    val archive: SemanticRuntimeArchive,
    val layout: SemanticRuntimeLayout,
    val canonicalJson: CanonicalRuntimeManifestJson,
) {
    companion object {
        private val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }

        /**
         * Proof transition: `String -> SemanticRuntimeManifestAdmission`.
         *
         * Establishes complete platform, architecture, compatibility, content digest, safe layout,
         * and derived runtime identity invariants. [SemanticRuntimeFailure.MANIFEST_INVALID] is the
         * closed expected failure. Raw JSON is permitted only at this embedded-resource boundary.
         */
        fun admit(rawJson: String): SemanticRuntimeManifestAdmission {
            val document = try {
                json.decodeFromString<ManifestDocument>(rawJson)
            } catch (_: RuntimeException) {
                return SemanticRuntimeManifestAdmission.Rejected(
                    SemanticRuntimeFailure.MANIFEST_INVALID,
                )
            }
            return document.admit(json)
        }
    }
}

sealed interface SemanticRuntimeManifestAdmission {
    data class Admitted(val manifest: SemanticRuntimeManifest) : SemanticRuntimeManifestAdmission
    data class Rejected(
        val failure: SemanticRuntimeFailure,
    ) : SemanticRuntimeManifestAdmission
}

/** Closed selection of the sole runtime artifact source. */
sealed interface SemanticRuntimeSource {
    data object Managed : SemanticRuntimeSource
    class PreseededArchive internal constructor(val archive: Path) : SemanticRuntimeSource

    companion object {
        /**
         * Proof transition: `String? -> SemanticRuntimeSourceSelection`.
         *
         * Establishes either managed acquisition or one absolute normalized preseeded archive.
         * [SemanticRuntimeFailure.SOURCE_INVALID] is the closed expected failure. Nullable raw text
         * is permitted only for extraction of the optional `KAST_RUNTIME_ARCHIVE` environment
         * value at this boundary.
         */
        fun select(rawArchive: String?): SemanticRuntimeSourceSelection {
            if (rawArchive == null) {
                return SemanticRuntimeSourceSelection.Managed(Managed)
            }
            if (rawArchive.isBlank()) {
                return SemanticRuntimeSourceSelection.Rejected(
                    SemanticRuntimeFailure.SOURCE_INVALID,
                )
            }
            val path = try {
                Path.of(rawArchive)
            } catch (_: InvalidPathException) {
                return SemanticRuntimeSourceSelection.Rejected(
                    SemanticRuntimeFailure.SOURCE_INVALID,
                )
            }
            return if (path.isAbsolute) {
                SemanticRuntimeSourceSelection.Preseeded(
                    PreseededArchive(path.normalize()),
                )
            } else {
                SemanticRuntimeSourceSelection.Rejected(
                    SemanticRuntimeFailure.SOURCE_INVALID,
                )
            }
        }
    }
}

sealed interface SemanticRuntimeSourceSelection {
    data class Managed(val source: SemanticRuntimeSource.Managed) : SemanticRuntimeSourceSelection
    data class Preseeded(
        val source: SemanticRuntimeSource.PreseededArchive,
    ) : SemanticRuntimeSourceSelection

    data class Rejected(val failure: SemanticRuntimeFailure) : SemanticRuntimeSourceSelection
}

@Serializable
private data class ManifestDocument(
    val schemaVersion: Int,
    val runtimeId: String,
    val productVersion: String,
    val platform: String,
    val architecture: String,
    val ideaBuild: String,
    val kotlinPluginBuild: String,
    val kastPluginSha256: String,
    val wireSchemaId: String,
    val archive: ArchiveDocument,
    val layout: LayoutDocument,
)

@Serializable
private data class ArchiveDocument(
    val fileName: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
)

@Serializable
private data class LayoutDocument(
    val executable: String,
    val requiredEntries: List<String>,
    val executableEntries: List<String>,
)

private fun ManifestDocument.admit(json: Json): SemanticRuntimeManifestAdmission {
    if (
        schemaVersion != MANIFEST_SCHEMA_VERSION ||
        platform != RuntimePlatform.MACOS.wireValue ||
        architecture != RuntimeArchitecture.AARCH64.wireValue ||
        layout.requiredEntries.isEmpty() || layout.executableEntries.isEmpty()
    ) return invalidManifest()

    val runtime = when (val refined = SemanticRuntimeId.parse(runtimeId)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return invalidManifest()
    }
    val product = when (val refined = RuntimeProductVersion.parse(productVersion)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return invalidManifest()
    }
    val idea = when (val refined = IntellijBuildIdentity.parse(ideaBuild)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return invalidManifest()
    }
    val kotlinPlugin = when (val refined = KotlinPluginBuildIdentity.parse(kotlinPluginBuild)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return invalidManifest()
    }
    val pluginDigest = when (val refined = RuntimeDigest.parse(kastPluginSha256)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return invalidManifest()
    }
    val schema = when (val refined = RuntimeWireSchemaIdentity.parse(wireSchemaId)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return invalidManifest()
    }
    val archiveDigest = when (val refined = RuntimeDigest.parse(archive.sha256)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return invalidManifest()
    }
    val archiveSize = when (val refined = RuntimeArchiveSize.parse(archive.bytes)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return invalidManifest()
    }
    val archiveName = when (val refined = RuntimeLayoutEntry.parse(archive.fileName)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return invalidManifest()
    }
    if (archiveName.value.contains('/')) return invalidManifest()
    val executable = when (val refined = RuntimeLayoutEntry.parse(layout.executable)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return invalidManifest()
    }
    val requiredEntries = when (val admitted = layout.requiredEntries.admitLayoutEntries()) {
        is LayoutEntriesAdmission.Admitted -> admitted.entries
        LayoutEntriesAdmission.Rejected -> return invalidManifest()
    }
    val executableEntries = when (val admitted = layout.executableEntries.admitLayoutEntries()) {
        is LayoutEntriesAdmission.Admitted -> admitted.entries
        LayoutEntriesAdmission.Rejected -> return invalidManifest()
    }
    if (executable !in requiredEntries || executable !in executableEntries) return invalidManifest()
    val uri = try {
        URI(archive.url)
    } catch (_: RuntimeException) {
        return invalidManifest()
    }
    if (!uri.isAbsolute || uri.scheme !in setOf("https", "http")) return invalidManifest()
    val expectedRuntime = SemanticRuntimeId.derive(
        listOf(
            platform,
            architecture,
            idea.value,
            kotlinPlugin.value,
            pluginDigest.value,
            schema.value,
            archiveDigest.value,
        ).joinToString("\n"),
    )
    if (runtime != expectedRuntime) return invalidManifest()
    val canonical = json.encodeToString(this)
    if (canonical != json.encodeToString(json.decodeFromString<ManifestDocument>(canonical))) {
        return invalidManifest()
    }
    return SemanticRuntimeManifestAdmission.Admitted(
        SemanticRuntimeManifest(
            runtime,
            product,
            RuntimePlatform.MACOS,
            RuntimeArchitecture.AARCH64,
            idea,
            kotlinPlugin,
            pluginDigest,
            schema,
            SemanticRuntimeArchive(archiveName, uri, archiveDigest, archiveSize),
            SemanticRuntimeLayout(executable, requiredEntries, executableEntries),
            CanonicalRuntimeManifestJson(canonical),
        ),
    )
}

private sealed interface LayoutEntriesAdmission {
    data class Admitted(val entries: List<RuntimeLayoutEntry>) : LayoutEntriesAdmission
    data object Rejected : LayoutEntriesAdmission
}

/**
 * Proof transition: `List<String> -> LayoutEntriesAdmission`.
 *
 * Establishes distinct, safe relative runtime-layout entries or the closed rejected state. Raw
 * entry text is permitted only at manifest admission.
 */
private fun List<String>.admitLayoutEntries(): LayoutEntriesAdmission {
    val entries = map { raw ->
        when (val refined = RuntimeLayoutEntry.parse(raw)) {
            is Refinement.Refined -> refined.value
            is Refinement.Rejected -> return LayoutEntriesAdmission.Rejected
        }
    }
    return if (entries.distinct().size == entries.size) {
        LayoutEntriesAdmission.Admitted(entries)
    } else {
        LayoutEntriesAdmission.Rejected
    }
}

private fun invalidManifest() = SemanticRuntimeManifestAdmission.Rejected(
    SemanticRuntimeFailure.MANIFEST_INVALID,
)
