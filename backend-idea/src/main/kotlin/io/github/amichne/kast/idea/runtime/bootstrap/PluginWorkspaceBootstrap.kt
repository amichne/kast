package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.workspaceDataDirectory
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.compatibility.PluginImplementationVersion
import io.github.amichne.kast.api.contract.compatibility.ProtocolRevision
import io.github.amichne.kast.api.contract.compatibility.RuntimeBackendKind
import io.github.amichne.kast.api.contract.compatibility.RuntimeCompatibilityFacts
import io.github.amichne.kast.api.contract.compatibility.RuntimeIdentity
import io.github.amichne.kast.api.contract.compatibility.RuntimeImplementationVersion
import io.github.amichne.kast.api.contract.compatibility.WorkspaceMetadataRevision
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

object PluginWorkspaceBootstrap {
    private val schemaVersion = WorkspaceMetadataRevision.CURRENT.value
    private const val metadataRelative = "workspace.json"
    private const val legacyMetadataRelative = ".kast/setup/workspace.json"

    fun prepare(request: PluginWorkspaceBootstrapRequest): PluginWorkspaceBootstrapResult =
        prepare(request, ::workspaceDataDirectory)

    internal fun prepare(
        request: PluginWorkspaceBootstrapRequest,
        resolveWorkspaceDataDirectory: (Path) -> Path,
    ): PluginWorkspaceBootstrapResult {
        if (!Files.isRegularFile(request.cliBinary)) {
            return PluginWorkspaceBootstrapResult.Rejected(
                "Kast CLI binary is missing at ${request.cliBinary}",
            )
        }
        val workspaceRoot = request.workspaceRoot.toAbsolutePath().normalize()
        val metadataPath = runCatching {
            resolveWorkspaceDataDirectory(workspaceRoot)
                .toAbsolutePath()
                .normalize()
                .resolve(metadataRelative)
        }.getOrElse { failure ->
            return PluginWorkspaceBootstrapResult.Rejected(
                "Could not resolve Kast workspace data for $workspaceRoot: " +
                    (failure.message ?: failure::class.java.simpleName),
            )
        }
        return try {
            writeMetadataAtomically(metadataPath, renderMetadata(request))
            removeLegacyMetadata(workspaceRoot)
            PluginWorkspaceBootstrapResult.Prepared(metadataPath, emptyList())
        } catch (failure: Exception) {
            PluginWorkspaceBootstrapResult.Rejected(
                "Could not prepare Kast workspace metadata at $metadataPath: " +
                    (failure.message ?: failure::class.java.simpleName),
            )
        }
    }

    private fun removeLegacyMetadata(workspaceRoot: Path) {
        val kastDirectory = workspaceRoot.resolve(".kast")
        val setupDirectory = kastDirectory.resolve("setup")
        if (!Files.isDirectory(kastDirectory, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isDirectory(setupDirectory, LinkOption.NOFOLLOW_LINKS)
        ) {
            return
        }
        val legacyMetadata = workspaceRoot.resolve(legacyMetadataRelative)
        if (!Files.isRegularFile(legacyMetadata, LinkOption.NOFOLLOW_LINKS)) return
        Files.delete(legacyMetadata)
        deleteIfEmpty(setupDirectory)
        deleteIfEmpty(kastDirectory)
    }

    private fun deleteIfEmpty(directory: Path) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return
        Files.newDirectoryStream(directory).use { entries ->
            if (entries.iterator().hasNext()) return
        }
        Files.deleteIfExists(directory)
    }

    private fun writeMetadataAtomically(
        target: Path,
        contents: String,
    ) {
        Files.createDirectories(target.parent)
        if (Files.isRegularFile(target) && Files.readString(target) == contents) return
        val staging = target.resolveSibling(".workspace-${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(staging, contents)
            try {
                Files.move(
                    staging,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    private fun renderMetadata(request: PluginWorkspaceBootstrapRequest): String {
        val compatibility = Json.encodeToString(
            RuntimeCompatibilityFacts(
                pluginVersion = PluginImplementationVersion(request.pluginVersion.value),
                cliVersion = request.cliVersion,
                protocolRevision = ProtocolRevision.CURRENT,
                workspaceMetadataRevision = WorkspaceMetadataRevision.CURRENT,
                readCapabilities = ReadCapability.entries.toSet(),
                mutationCapabilities = MutationCapability.entries.toSet(),
                runtimeIdentity = RuntimeIdentity(
                    implementationVersion = RuntimeImplementationVersion(request.pluginVersion.value),
                    backendKind = RuntimeBackendKind.IDEA,
                ),
            ),
        )
        return """
        |{
        |  "schemaVersion": $schemaVersion,
        |  "preparedBy": "kast-intellij-plugin",
        |  "workspaceRoot": ${jsonString(request.workspaceRoot.toString())},
        |  "cliBinary": ${jsonString(request.cliBinary.toString())},
        |  "backend": "idea",
        |  "socketPath": ${jsonString(request.socketPath.toAbsolutePath().normalize().toString())},
        |  "compatibility": $compatibility,
        |  "requiredArtifacts": [
        |    ${jsonString(metadataRelative)}
        |  ]
        |}
        |""".trimMargin()
    }

    private fun jsonString(value: String): String = Json.encodeToString(value)
}
