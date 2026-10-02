package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/** Implementation identity is retained independently of transport admission. */
typealias HostedPluginVersion = KastPluginVersion

data class HostProvenance(val version: HostedPluginVersion)

data class HostedContract(
    val runtimeProtocolIdentity: RuntimeProtocolIdentity,
    val operationRegistryDigest: OperationRegistryDigest,
    val wireSchemaDigest: WireSchemaDigest,
    val capabilities: IdeHostCapabilitySet,
)

/** Required evidence at the host/control trust boundary; release versions never enter this shape. */
@Serializable
data class HostedContractDocument(
    @Required val type: HostedContractType = HostedContractType.HOSTED_CONTRACT,
    val runtimeProtocolIdentity: String,
    val operationRegistryDigest: String,
    val wireSchemaDigest: String,
    val capabilities: List<String>,
) {
    fun admit(): Refinement<HostedContract, IdeHostCompatibilityFailure> {
        val protocol =
            when (val parsed = RuntimeProtocolIdentity.parse(runtimeProtocolIdentity)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
        val operations =
            when (val parsed = OperationRegistryDigest.parse(operationRegistryDigest)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
        val schema =
            when (val parsed = WireSchemaDigest.parse(wireSchemaDigest)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
        val capabilities =
            when (val parsed = IdeHostCapabilitySet.parse(capabilities)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
        return Refinement.Refined(
            HostedContract(
                runtimeProtocolIdentity = protocol,
                operationRegistryDigest = operations,
                wireSchemaDigest = schema,
                capabilities = capabilities,
            )
        )
    }
}

@Serializable
enum class HostedContractType {
    HOSTED_CONTRACT
}

/** Exact equality is the sole hosted compatibility rule. No release identity is accepted as an input. */
object HostedCompatibilityPolicy {
    fun admit(
        required: HostedContract,
        observed: HostedContract,
    ): Refinement<HostedContract, IdeHostCompatibilityFailure> {
        val mismatch =
            when {
                observed.runtimeProtocolIdentity != required.runtimeProtocolIdentity ->
                    IdeHostCompatibilityMismatch.RuntimeProtocol(
                        required.runtimeProtocolIdentity,
                        observed.runtimeProtocolIdentity,
                    )
                observed.operationRegistryDigest != required.operationRegistryDigest ->
                    IdeHostCompatibilityMismatch.OperationRegistry(
                        required.operationRegistryDigest,
                        observed.operationRegistryDigest,
                    )
                observed.wireSchemaDigest != required.wireSchemaDigest ->
                    IdeHostCompatibilityMismatch.WireSchema(required.wireSchemaDigest, observed.wireSchemaDigest)
                observed.capabilities != required.capabilities ->
                    IdeHostCompatibilityMismatch.Capabilities(required.capabilities, observed.capabilities)
                else -> return Refinement.Refined(observed)
            }
        return Refinement.Rejected(IdeHostCompatibilityFailure.Mismatch(mismatch))
    }
}

/** Control requirements contain boundary facts and platform admission, with no implementation provenance. */
data class HostedCompatibilityRequirements(
    val ideBuild: IdeBuildIdentity,
    val kotlinPluginBuild: KotlinPluginBuildIdentity,
    val contract: HostedContract,
) {
    fun admit(document: HostedCompatibilityDocument): IdeHostCompatibilityAdmission {
        val observed =
            when (val parsed = AdmittedIdeHostCompatibility.parse(document.candidate())) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return IdeHostCompatibilityAdmission.Rejected(parsed.failure)
            }
        if (observed.ideBuild.releaseLine != ideBuild.releaseLine)
            return IdeHostCompatibilityAdmission.Rejected(
                IdeHostCompatibilityFailure.Mismatch(IdeHostCompatibilityMismatch.IdeBuild(ideBuild, observed.ideBuild))
            )
        if (observed.kotlinPluginBuild.releaseLine != kotlinPluginBuild.releaseLine)
            return IdeHostCompatibilityAdmission.Rejected(
                IdeHostCompatibilityFailure.Mismatch(
                    IdeHostCompatibilityMismatch.KotlinPluginBuild(kotlinPluginBuild, observed.kotlinPluginBuild)
                )
            )
        return when (val admitted = HostedCompatibilityPolicy.admit(contract, observed.hostedContract)) {
            is Refinement.Refined -> IdeHostCompatibilityAdmission.Admitted(observed)
            is Refinement.Rejected -> IdeHostCompatibilityAdmission.Rejected(admitted.failure)
        }
    }
}
