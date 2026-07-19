package io.github.amichne.kast.api.contract.compatibility

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
data class RuntimeCompatibilityMatrix(
    @DocField(description = "Explicit release and runtime combinations supported by this release.")
    val supportedPairs: Set<SupportedRuntimeCompatibilityPair>,
) {
    init {
        require(supportedPairs.map { pair -> pair.facts.compatibilityKey() }.toSet().size == supportedPairs.size) {
            "Runtime compatibility rows must have unique negotiation facts"
        }
    }

    fun assess(
        facts: RuntimeCompatibilityFacts,
        operationCapability: RuntimeCapability? = null,
    ): RuntimeCompatibilityOutcome {
        val releaseRows = supportedPairs.filter { pair ->
            pair.facts.pluginVersion == facts.pluginVersion &&
                pair.facts.cliVersion == facts.cliVersion
        }
        if (releaseRows.isEmpty()) {
            return RuntimeCompatibilityOutcome.UpdateRequired(
                RuntimeCompatibilityUpdateRequirement.UnsupportedReleasePair(
                    pluginVersion = facts.pluginVersion,
                    cliVersion = facts.cliVersion,
                ),
            )
        }

        val protocolRows = releaseRows.filter { pair ->
            pair.facts.protocolRevision == facts.protocolRevision
        }
        if (protocolRows.isEmpty()) {
            return RuntimeCompatibilityOutcome.UpdateRequired(
                RuntimeCompatibilityUpdateRequirement.UnsupportedProtocolRevision(
                    actual = facts.protocolRevision,
                    supported = releaseRows.mapTo(linkedSetOf()) { pair ->
                        pair.facts.protocolRevision
                    },
                ),
            )
        }

        val metadataRows = protocolRows.filter { pair ->
            pair.facts.workspaceMetadataRevision == facts.workspaceMetadataRevision
        }
        if (metadataRows.isEmpty()) {
            return RuntimeCompatibilityOutcome.UpdateRequired(
                RuntimeCompatibilityUpdateRequirement.UnsupportedWorkspaceMetadataRevision(
                    actual = facts.workspaceMetadataRevision,
                    supported = protocolRows.mapTo(linkedSetOf()) { pair ->
                        pair.facts.workspaceMetadataRevision
                    },
                ),
            )
        }

        val runtimeRows = metadataRows.filter { pair ->
            pair.facts.runtimeIdentity == facts.runtimeIdentity
        }
        if (runtimeRows.isEmpty()) {
            return RuntimeCompatibilityOutcome.UpdateRequired(
                RuntimeCompatibilityUpdateRequirement.UnsupportedRuntimeIdentity(
                    actual = facts.runtimeIdentity,
                    supported = metadataRows.mapTo(linkedSetOf()) { pair ->
                        pair.facts.runtimeIdentity
                    },
                ),
            )
        }

        val supportedPair = runtimeRows.single()
        val missingRequiredCapability = supportedPair.requiredCapabilities
            .firstOrNull { capability -> !facts.advertises(capability) }
        if (missingRequiredCapability != null) {
            return RuntimeCompatibilityOutcome.UpdateRequired(
                RuntimeCompatibilityUpdateRequirement.MissingRequiredCapability(
                    missingRequiredCapability,
                ),
            )
        }
        if (operationCapability != null && !facts.advertises(operationCapability)) {
            return RuntimeCompatibilityOutcome.MissingCapability(operationCapability)
        }
        return RuntimeCompatibilityOutcome.Compatible(facts)
    }
}

private data class RuntimeCompatibilityKey(
    val pluginVersion: PluginImplementationVersion,
    val cliVersion: CliImplementationVersion,
    val protocolRevision: ProtocolRevision,
    val workspaceMetadataRevision: WorkspaceMetadataRevision,
    val runtimeIdentity: RuntimeIdentity,
)

private fun RuntimeCompatibilityFacts.compatibilityKey(): RuntimeCompatibilityKey =
    RuntimeCompatibilityKey(
        pluginVersion = pluginVersion,
        cliVersion = cliVersion,
        protocolRevision = protocolRevision,
        workspaceMetadataRevision = workspaceMetadataRevision,
        runtimeIdentity = runtimeIdentity,
    )
