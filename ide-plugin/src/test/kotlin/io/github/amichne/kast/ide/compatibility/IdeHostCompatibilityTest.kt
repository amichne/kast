package io.github.amichne.kast.ide.compatibility

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCapability
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class IdeHostCompatibilityTest {
    @Test
    fun `the one supported tuple retains all admitted identities and canonical capabilities`() {
        val candidate = supportedCandidate("0.28.1-26-ge4c840448")
        val policy = supportedPolicy(candidate)

        val admitted = when (val result = policy.admit(candidate)) {
            is IdeHostCompatibilityAdmission.Admitted -> result.compatibility
            is IdeHostCompatibilityAdmission.Rejected -> fail("exact tuple rejected: ${result.failure}")
        }

        assertEquals(HOSTED_IDE_BUILD, admitted.ideBuild.value)
        assertEquals(HOSTED_KOTLIN_PLUGIN_BUILD, admitted.kotlinPluginBuild.value)
        assertEquals(candidate.kastPluginVersion, admitted.kastPluginVersion.value)
        assertEquals(HOSTED_RUNTIME_PROTOCOL, admitted.runtimeProtocolIdentity.value)
        assertEquals(OPERATION_REGISTRY_DIGEST, admitted.operationRegistryDigest.value)
        assertEquals(WIRE_SCHEMA_DIGEST, admitted.wireSchemaDigest.value)
        assertEquals(IdeHostCapability.entries, admitted.capabilities.capabilities)
        assertEquals(
            listOf(
                "workspace.inspect",
                "symbol.discover",
                "symbol.resolve",
                "symbol.describe",
            ),
            admitted.capabilities.capabilities.map { it.operation.id.value },
        )
    }

    @Test
    fun `generated report decodes into the same admitted compatibility value`() {
        val pluginVersion = System.getProperty("kast.ide.compatibility.plugin-version")
            ?: fail("missing generated report plugin version")
        val policy = supportedPolicy(supportedCandidate(pluginVersion))
        val reportPath = System.getProperty("kast.ide.compatibility.report")
            ?.let(Path::of)
            ?: fail("missing generated report path")

        val admitted = when (
            val result = IdeHostCompatibilityMetadata.decode(
                Files.readString(reportPath),
                policy,
            )
        ) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> fail("generated report rejected: ${result.failure}")
        }

        assertEquals(pluginVersion, admitted.kastPluginVersion.value)
        assertEquals(HOSTED_IDE_BUILD, admitted.ideBuild.value)
        assertEquals(HOSTED_KOTLIN_PLUGIN_BUILD, admitted.kotlinPluginBuild.value)
        assertEquals(OPERATION_REGISTRY_DIGEST, admitted.operationRegistryDigest.value)
        assertEquals(WIRE_SCHEMA_DIGEST, admitted.wireSchemaDigest.value)
    }
}

internal const val HOSTED_IDE_BUILD = "262.9437.185"
internal const val HOSTED_KOTLIN_PLUGIN_BUILD = "262.9437.185-IJ"
internal const val HOSTED_RUNTIME_PROTOCOL = "kast.ide-hosted.runtime.v1"
internal const val OPERATION_REGISTRY_DIGEST =
    "sha256:2d4b7e46638f44c2ec57e5aa3654c07319a68fc1596c2826ac58034dba5211fc"
internal const val WIRE_SCHEMA_DIGEST =
    "sha256:52966aebe99d44ba6754a71c563f444c851d8727106bbb754ae51822eec36fc7"

internal val HOSTED_CAPABILITIES = listOf(
    "workspace.inspect",
    "symbol.discover",
    "symbol.resolve",
    "symbol.describe",
)

internal fun supportedCandidate(pluginVersion: String) = IdeHostCompatibilityCandidate(
    ideBuild = HOSTED_IDE_BUILD,
    kotlinPluginBuild = HOSTED_KOTLIN_PLUGIN_BUILD,
    kastPluginVersion = pluginVersion,
    runtimeProtocolIdentity = HOSTED_RUNTIME_PROTOCOL,
    operationRegistryDigest = OPERATION_REGISTRY_DIGEST,
    wireSchemaDigest = WIRE_SCHEMA_DIGEST,
    capabilities = HOSTED_CAPABILITIES,
)

internal fun supportedPolicy(
    candidate: IdeHostCompatibilityCandidate,
): IdeHostCompatibilityPolicy = when (val result = IdeHostCompatibilityPolicy.define(candidate)) {
    is Refinement.Refined -> result.value
    is Refinement.Rejected -> fail("supported policy rejected: ${result.failure}")
}
