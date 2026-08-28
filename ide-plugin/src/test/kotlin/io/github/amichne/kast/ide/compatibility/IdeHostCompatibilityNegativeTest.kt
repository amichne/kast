package io.github.amichne.kast.ide.compatibility

import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCapability
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityField
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityIdentityField
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilitySyntaxFailure
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
                IdeHostCompatibilityIdentityField.IDE_BUILD,
            expected.copy(kotlinPluginBuild = "262.9437.186-IJ") to
                IdeHostCompatibilityIdentityField.KOTLIN_PLUGIN_BUILD,
            expected.copy(kastPluginVersion = "0.28.2") to
                IdeHostCompatibilityIdentityField.KAST_PLUGIN_VERSION,
            expected.copy(runtimeProtocolIdentity = "kast.ide-hosted.runtime.v2") to
                IdeHostCompatibilityIdentityField.RUNTIME_PROTOCOL_IDENTITY,
            expected.copy(operationRegistryDigest = digest('b')) to
                IdeHostCompatibilityIdentityField.OPERATION_REGISTRY_DIGEST,
            expected.copy(wireSchemaDigest = digest('c')) to
                IdeHostCompatibilityIdentityField.WIRE_SCHEMA_DIGEST,
        ).forEach { (candidate, field) ->
            assertRejected(candidate, IdeHostCompatibilityFailure.Mismatch(field))
        }
    }

    @Test
    fun `unknown duplicate missing extra and reordered capabilities fail closed`() {
        assertRejected(
            expected.copy(capabilities = HOSTED_CAPABILITIES + "diagnostic.check"),
            IdeHostCompatibilityFailure.CapabilitySetMismatch,
        )
        assertRejected(
            expected.copy(capabilities = HOSTED_CAPABILITIES + "other.read"),
            IdeHostCompatibilityFailure.UnknownCapability(operationId("other.read")),
        )
        assertRejected(
            expected.copy(capabilities = listOf("a".repeat(97))),
            IdeHostCompatibilityFailure.Malformed(
                IdeHostCompatibilityField.CAPABILITIES,
                IdeHostCompatibilitySyntaxFailure.TOO_LONG,
            ),
        )
        assertRejected(
            expected.copy(
                capabilities = listOf(HOSTED_CAPABILITIES.first()) + HOSTED_CAPABILITIES,
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

    @Test
    fun `metadata boundary rejects malformed schema and task documents as finite data`() {
        val document = IdeHostCompatibilityReportDocument(
            schemaVersion = 1,
            taskId = "IDE-HOST-COMPATIBILITY",
            ideBuild = expected.ideBuild,
            kotlinPluginBuild = expected.kotlinPluginBuild,
            kastPluginVersion = expected.kastPluginVersion,
            runtimeProtocolIdentity = expected.runtimeProtocolIdentity,
            operationRegistryDigest = expected.operationRegistryDigest,
            wireSchemaDigest = expected.wireSchemaDigest,
            capabilities = expected.capabilities.map { operation ->
                HostedCapabilityReportDocument(
                    operation,
                    if (operation == "change.plan") listOf("add-declaration") else emptyList(),
                )
            },
        )
        assertMetadataRejected("{", IdeHostCompatibilityMetadataFailure.MalformedDocument)
        assertMetadataRejected(
            metadataJson.encodeToString(
                IdeHostCompatibilityReportDocument.serializer(),
                document.copy(schemaVersion = 2),
            ),
            IdeHostCompatibilityMetadataFailure.UnsupportedSchemaVersion,
        )
        assertMetadataRejected(
            metadataJson.encodeToString(
                IdeHostCompatibilityReportDocument.serializer(),
                document.copy(taskId = "WRONG-TASK"),
            ),
            IdeHostCompatibilityMetadataFailure.WrongTaskIdentity,
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

    private fun assertMetadataRejected(
        raw: String,
        expectedFailure: IdeHostCompatibilityMetadataFailure,
    ) {
        when (val result = IdeHostCompatibilityMetadata.decode(raw, policy)) {
            is Refinement.Refined -> fail("metadata unexpectedly admitted")
            is Refinement.Rejected -> assertEquals(expectedFailure, result.failure)
        }
    }

    private fun malformed(
        field: IdeHostCompatibilityField,
        syntax: IdeHostCompatibilitySyntaxFailure =
            IdeHostCompatibilitySyntaxFailure.INVALID_FORMAT,
    ) = IdeHostCompatibilityFailure.Malformed(field, syntax)

    private fun digest(character: Char): String = "sha256:" + character.toString().repeat(64)

    private fun operationId(raw: String): OperationId = when (val parsed = OperationId.parse(raw)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> fail("invalid test operation id: ${parsed.failure}")
    }

    private companion object {
        val metadataJson = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
            isLenient = false
        }
    }
}
