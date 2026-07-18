package io.github.amichne.kast.api.contract.compatibility

import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
data class RuntimeCompatibilityFacts(
    @DocField(description = "IDEA plugin release version reported by the workspace.")
    val pluginVersion: PluginImplementationVersion,
    @DocField(description = "CLI release version reported by the workspace.")
    val cliVersion: CliImplementationVersion,
    @DocField(description = "Full source revision embedded in the IDEA plugin artifact.")
    val pluginRevision: ReleaseRevision,
    @DocField(description = "Full source revision embedded in the CLI artifact.")
    val cliRevision: ReleaseRevision,
    @DocField(description = "Negotiation protocol revision reported by the workspace.")
    val protocolRevision: ProtocolRevision,
    @DocField(description = "Revision of the exact-workspace-root metadata document.")
    val workspaceMetadataRevision: WorkspaceMetadataRevision,
    @DocField(description = "Read operations advertised by the runtime.")
    val readCapabilities: Set<ReadCapability>,
    @DocField(description = "Mutation operations advertised by the runtime.")
    val mutationCapabilities: Set<MutationCapability>,
    @DocField(description = "Implementation and backend identity of the runtime host.")
    val runtimeIdentity: RuntimeIdentity,
) {
    fun advertises(capability: RuntimeCapability): Boolean =
        when (capability) {
            is RuntimeCapability.Read -> capability.capability in readCapabilities
            is RuntimeCapability.Mutation -> capability.capability in mutationCapabilities
        }
}
