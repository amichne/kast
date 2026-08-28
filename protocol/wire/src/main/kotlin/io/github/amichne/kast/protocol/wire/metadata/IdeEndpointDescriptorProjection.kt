package io.github.amichne.kast.protocol.wire.metadata

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy

private const val FIXTURE_IDE_BUILD = "262.9437.185"
private const val FIXTURE_KOTLIN_PLUGIN_BUILD = "262.9437.185-IJ"
private const val FIXTURE_KAST_PLUGIN_VERSION = "1.2.3"
private const val FIXTURE_RUNTIME_PROTOCOL = "kast.ide-hosted.runtime.v1"
private const val FIXTURE_REGISTRY_DIGEST =
    "sha256:1111111111111111111111111111111111111111111111111111111111111111"
private const val FIXTURE_WIRE_DIGEST =
    "sha256:2222222222222222222222222222222222222222222222222222222222222222"
private val FIXTURE_CAPABILITIES = listOf(
    "workspace.inspect",
    "symbol.discover",
    "symbol.resolve",
    "symbol.describe",
)

/**
 * Deterministic canonical KVP-013 schema/codec fixture used as the gate's proof artifact.
 *
 * Its compatibility values are explicit fixtures, not the KVP-012 supported host tuple. The
 * KVP-012 predecessor receipt independently binds the physical supported compatibility evidence.
 */
internal object IdeEndpointDescriptorProjection {
    val document: String = projectionDescriptor().encode().document

    /** Prints the canonical descriptor document for the Gradle-owned report boundary. */
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.isEmpty()) { "IDE endpoint descriptor projection accepts no arguments" }
        print(document)
    }
}

private fun projectionDescriptor(): IdeEndpointDescriptorV2 {
    val policy = when (
        val refinement = IdeHostCompatibilityPolicy.define(
            IdeHostCompatibilityCandidate(
                FIXTURE_IDE_BUILD,
                FIXTURE_KOTLIN_PLUGIN_BUILD,
                FIXTURE_KAST_PLUGIN_VERSION,
                FIXTURE_RUNTIME_PROTOCOL,
                FIXTURE_REGISTRY_DIGEST,
                FIXTURE_WIRE_DIGEST,
                FIXTURE_CAPABILITIES,
            ),
        )
    ) {
        is Refinement.Refined -> refinement.value
        is Refinement.Rejected -> error(
            "invalid compiler-owned endpoint compatibility projection: ${refinement.failure}",
        )
    }
    return when (
        val admission = IdeEndpointDescriptorV2.create(
            IdeEndpointDescriptorCandidate(
                schema = IdeEndpointSchema.V2.identity,
                canonicalRoot = "/workspace/kast",
                hostKind = IdeEndpointHostKind.IDE_PROJECT.identity,
                processId = 1,
                ideBuild = FIXTURE_IDE_BUILD,
                kotlinPluginBuild = FIXTURE_KOTLIN_PLUGIN_BUILD,
                kastPluginVersion = FIXTURE_KAST_PLUGIN_VERSION,
                runtimeProtocolIdentity = FIXTURE_RUNTIME_PROTOCOL,
                operationRegistryDigest = FIXTURE_REGISTRY_DIGEST,
                wireSchemaDigest = FIXTURE_WIRE_DIGEST,
                socketPath = "/tmp/kast-ide.sock",
                framing = IdeEndpointFraming.LENGTH_PREFIXED_JSON_V1.identity,
                runtimeEpoch = 0,
                capabilities = FIXTURE_CAPABILITIES,
            ),
            policy,
        )
    ) {
        is IdeEndpointDescriptorAdmission.Admitted -> admission.descriptor
        is IdeEndpointDescriptorAdmission.Rejected -> error(
            "invalid compiler-owned endpoint descriptor projection: ${admission.failure}",
        )
    }
}
