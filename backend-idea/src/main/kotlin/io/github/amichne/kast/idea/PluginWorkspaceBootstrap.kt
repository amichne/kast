package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.defaultSocketPath
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

object PluginWorkspaceBootstrap {
    private val schemaVersion = WorkspaceMetadataRevision.CURRENT.value
    private const val metadataRelative = ".kast/setup/workspace.json"

    fun prepare(request: PluginWorkspaceBootstrapRequest): PluginWorkspaceBootstrapResult {
        if (!Files.isRegularFile(request.cliBinary)) {
            return PluginWorkspaceBootstrapResult.Rejected(
                "Kast CLI binary is missing at ${request.cliBinary}",
            )
        }
        val workspaceRoot = request.workspaceRoot.toAbsolutePath().normalize()
        val metadataPath = workspaceRoot.resolve(metadataRelative)
        writeMetadataAtomically(metadataPath, renderMetadata(request))
        return PluginWorkspaceBootstrapResult.Prepared(metadataPath, emptyList())
    }

    private fun writeMetadataAtomically(
        target: Path,
        contents: String,
    ) {
        Files.createDirectories(target.parent)
        if (Files.isRegularFile(target) && Files.readString(target) == contents) return
        val staging = target.resolveSibling(".workspace-${UUID.randomUUID()}.tmp")
        Files.writeString(staging, contents)
        runCatching {
            Files.move(
                staging,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
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
        |  "socketPath": ${jsonString(defaultSocketPath(request.workspaceRoot).toString())},
        |  "compatibility": $compatibility,
        |  "requiredArtifacts": [
        |    ${jsonString(metadataRelative)}
        |  ]
        |}
        |""".trimMargin()
    }

    private fun jsonString(value: String): String = Json.encodeToString(value)
}
