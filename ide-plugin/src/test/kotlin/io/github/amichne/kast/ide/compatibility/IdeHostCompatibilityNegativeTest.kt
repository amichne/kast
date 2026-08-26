package io.github.amichne.kast.ide.compatibility

import io.github.amichne.kast.protocol.contract.IdeHostCapability
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityField
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilitySyntaxFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class IdeHostCompatibilityNegativeTest {
    private val expected = supportedCandidate("0.28.1-26-ge4c840448")
    private val policy = supportedPolicy(expected)

    @Test
    fun `every malformed identity remains a field-specific finite rejection`() {
        listOf(
            expected.copy(ideBuild = "") to malformed(
                IdeHostCompatibilityField.IDE_BUILD,
                IdeHostCompatibilitySyntaxFailure.BLANK,
            ),
            expected.copy(kotlinPluginBuild = "2.4.10") to malformed(
                IdeHostCompatibilityField.KOTLIN_PLUGIN_BUILD,
            ),
            expected.copy(kastPluginVersion = "dev") to malformed(
                IdeHostCompatibilityField.KAST_PLUGIN_VERSION,
            ),
            expected.copy(runtimeProtocolIdentity = "runtime") to malformed(
                IdeHostCompatibilityField.RUNTIME_PROTOCOL_IDENTITY,
            ),
            expected.copy(operationRegistryDigest = "sha256:abc") to malformed(
                IdeHostCompatibilityField.OPERATION_REGISTRY_DIGEST,
            ),
            expected.copy(wireSchemaDigest = "sha256:abc") to malformed(
                IdeHostCompatibilityField.WIRE_SCHEMA_DIGEST,
            ),
            expected.copy(capabilities = listOf("not a capability")) to malformed(
                IdeHostCompatibilityField.CAPABILITIES,
            ),
        ).forEach { (candidate, failure) -> assertRejected(candidate, failure) }
    }

    @Test
    fun `each independently substituted identity is rejected as a mismatch`() {
        listOf(
            expected.copy(ideBuild = "262.9437.186") to
                IdeHostCompatibilityField.IDE_BUILD,
            expected.copy(kotlinPluginBuild = "262.9437.186-IJ") to
                IdeHostCompatibilityField.KOTLIN_PLUGIN_BUILD,
            expected.copy(kastPluginVersion = "0.28.2") to
                IdeHostCompatibilityField.KAST_PLUGIN_VERSION,
            expected.copy(runtimeProtocolIdentity = "kast.ide-hosted.runtime.v2") to
                IdeHostCompatibilityField.RUNTIME_PROTOCOL_IDENTITY,
            expected.copy(operationRegistryDigest = digest('b')) to
                IdeHostCompatibilityField.OPERATION_REGISTRY_DIGEST,
            expected.copy(wireSchemaDigest = digest('c')) to
                IdeHostCompatibilityField.WIRE_SCHEMA_DIGEST,
        ).forEach { (candidate, field) ->
            assertRejected(candidate, IdeHostCompatibilityFailure.Mismatch(field))
        }
    }

    @Test
    fun `unknown duplicate missing extra and reordered capabilities fail closed`() {
        assertRejected(
            expected.copy(capabilities = HOSTED_CAPABILITIES + "diagnostic.check"),
            IdeHostCompatibilityFailure.UnknownCapability,
        )
        assertRejected(
            expected.copy(
                capabilities = listOf(
                    "workspace.inspect",
                    "workspace.inspect",
                    "symbol.discover",
                    "symbol.resolve",
                    "symbol.describe",
                ),
            ),
            IdeHostCompatibilityFailure.DuplicateCapability(IdeHostCapability.WORKSPACE_INSPECT),
        )
        assertRejected(
            expected.copy(capabilities = HOSTED_CAPABILITIES.dropLast(1)),
            IdeHostCompatibilityFailure.CapabilitySetMismatch,
        )
        assertRejected(
            expected.copy(capabilities = HOSTED_CAPABILITIES.reversed()),
            IdeHostCompatibilityFailure.CapabilitySetMismatch,
        )
    }

    private fun assertRejected(
        candidate: IdeHostCompatibilityCandidate,
        expectedFailure: IdeHostCompatibilityFailure,
    ) {
        when (val result = policy.admit(candidate)) {
            is IdeHostCompatibilityAdmission.Admitted -> fail("candidate unexpectedly admitted")
            is IdeHostCompatibilityAdmission.Rejected ->
                assertEquals(expectedFailure, result.failure)
        }
    }

    private fun malformed(
        field: IdeHostCompatibilityField,
        syntax: IdeHostCompatibilitySyntaxFailure =
            IdeHostCompatibilitySyntaxFailure.INVALID_FORMAT,
    ) = IdeHostCompatibilityFailure.Malformed(field, syntax)

    private fun digest(character: Char): String = "sha256:" + character.toString().repeat(64)
}
