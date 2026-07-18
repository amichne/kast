package io.github.amichne.kast.api.contract.compatibility

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface RuntimeCompatibilityUpdateRequirement {
    @Serializable
    @SerialName("UNSUPPORTED_RELEASE_PAIR")
    data class UnsupportedReleasePair(
        @DocField(description = "IDEA plugin release version that has no explicit compatibility row.")
        val pluginVersion: PluginImplementationVersion,
        @DocField(description = "CLI release version that has no explicit compatibility row.")
        val cliVersion: CliImplementationVersion,
        @DocField(description = "Full source revision embedded in the IDEA plugin artifact.")
        val pluginRevision: ReleaseRevision,
        @DocField(description = "Full source revision embedded in the CLI artifact.")
        val cliRevision: ReleaseRevision,
    ) : RuntimeCompatibilityUpdateRequirement

    @Serializable
    @SerialName("MISMATCHED_RELEASE_REVISION")
    data class MismatchedReleaseRevision(
        @DocField(description = "Full source revision embedded in the IDEA plugin artifact.")
        val pluginRevision: ReleaseRevision,
        @DocField(description = "Full source revision embedded in the CLI artifact.")
        val cliRevision: ReleaseRevision,
    ) : RuntimeCompatibilityUpdateRequirement {
        init {
            require(pluginRevision != cliRevision) { "Mismatched release revisions must differ" }
        }
    }

    @Serializable
    @SerialName("UNSUPPORTED_PROTOCOL_REVISION")
    data class UnsupportedProtocolRevision(
        @DocField(description = "Protocol revision reported by the workspace.")
        val actual: ProtocolRevision,
        @DocField(description = "Protocol revisions supported for the reported release pair.")
        val supported: Set<ProtocolRevision>,
    ) : RuntimeCompatibilityUpdateRequirement {
        init {
            require(supported.isNotEmpty()) { "Supported protocol revisions must not be empty" }
            require(actual !in supported) { "Actual protocol revision must be unsupported" }
        }
    }

    @Serializable
    @SerialName("UNSUPPORTED_WORKSPACE_METADATA_REVISION")
    data class UnsupportedWorkspaceMetadataRevision(
        @DocField(description = "Workspace metadata revision reported by the workspace.")
        val actual: WorkspaceMetadataRevision,
        @DocField(description = "Workspace metadata revisions supported for the reported release pair.")
        val supported: Set<WorkspaceMetadataRevision>,
    ) : RuntimeCompatibilityUpdateRequirement {
        init {
            require(supported.isNotEmpty()) { "Supported workspace metadata revisions must not be empty" }
            require(actual !in supported) { "Actual workspace metadata revision must be unsupported" }
        }
    }

    @Serializable
    @SerialName("UNSUPPORTED_RUNTIME_IDENTITY")
    data class UnsupportedRuntimeIdentity(
        @DocField(description = "Runtime identity reported by the workspace.")
        val actual: RuntimeIdentity,
        @DocField(description = "Runtime identities supported for the reported release and revisions.")
        val supported: Set<RuntimeIdentity>,
    ) : RuntimeCompatibilityUpdateRequirement {
        init {
            require(supported.isNotEmpty()) { "Supported runtime identities must not be empty" }
            require(actual !in supported) { "Actual runtime identity must be unsupported" }
        }
    }

    @Serializable
    @SerialName("MISSING_REQUIRED_CAPABILITY")
    data class MissingRequiredCapability(
        @DocField(description = "Matrix-required capability absent from the runtime advertisement.")
        val capability: RuntimeCapability,
    ) : RuntimeCompatibilityUpdateRequirement
}
