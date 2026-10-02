package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable

/** Required live implementation provenance and the complete provided contract. */
@Serializable
data class HostedCompatibilityDocument(
    val ideBuild: String,
    val kotlinPluginBuild: String,
    val hostedPluginVersion: String,
    val hostedContract: HostedContractDocument,
) {
    fun candidate(): IdeHostCompatibilityCandidate =
        IdeHostCompatibilityCandidate(
            ideBuild = ideBuild,
            kotlinPluginBuild = kotlinPluginBuild,
            kastPluginVersion = hostedPluginVersion,
            runtimeProtocolIdentity = hostedContract.runtimeProtocolIdentity,
            operationRegistryDigest = hostedContract.operationRegistryDigest,
            wireSchemaDigest = hostedContract.wireSchemaDigest,
            capabilities = hostedContract.capabilities,
        )
}
