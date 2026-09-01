package io.github.amichne.kast.ide.compatibility

import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCapability
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityField
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityMismatch
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
            Triple(
                expected.copy(ideBuild = "262.9437.186"),
                IdeHostCompatibilityField.IDE_BUILD,
                expected.ideBuild to "262.9437.186",
            ),
            Triple(
                expected.copy(kotlinPluginBuild = "262.9437.186-IJ"),
                IdeHostCompatibilityField.KOTLIN_PLUGIN_BUILD,
                expected.kotlinPluginBuild to "262.9437.186-IJ",
            ),
            Triple(
                expected.copy(kastPluginVersion = "0.28.2"),
                IdeHostCompatibilityField.KAST_PLUGIN_VERSION,
                expected.kastPluginVersion to "0.28.2",
            ),
            Triple(
                expected.copy(runtimeProtocolIdentity = "kast.ide-hosted.runtime.v2"),
                IdeHostCompatibilityField.RUNTIME_PROTOCOL_IDENTITY,
                expected.runtimeProtocolIdentity to "kast.ide-hosted.runtime.v2",
            ),
            Triple(
                expected.copy(operationRegistryDigest = digest('b')),
                IdeHostCompatibilityField.OPERATION_REGISTRY_DIGEST,
                expected.operationRegistryDigest to digest('b'),
            ),
            Triple(
                expected.copy(wireSchemaDigest = digest('c')),
                IdeHostCompatibilityField.WIRE_SCHEMA_DIGEST,
                expected.wireSchemaDigest to digest('c'),
            ),
        ).forEach { (candidate, field, values) ->
            val mismatch = rejectedMismatch(candidate)
            assertEquals(field, mismatch.field)
            assertEquals(values, mismatch.identityValues())
        }
    }

    @Test
    fun `unknown duplicate missing and reordered capabilities fail closed`() {
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
            rejectedCapabilityMismatch(HOSTED_CAPABILITIES.dropLast(1)),
        )
        assertRejected(
            expected.copy(capabilities = HOSTED_CAPABILITIES.reversed()),
            rejectedCapabilityMismatch(HOSTED_CAPABILITIES.reversed()),
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

    private fun rejectedMismatch(
        candidate: IdeHostCompatibilityCandidate,
    ): IdeHostCompatibilityMismatch = when (val result = policy.admit(candidate)) {
        is IdeHostCompatibilityAdmission.Admitted -> fail("candidate unexpectedly admitted")
        is IdeHostCompatibilityAdmission.Rejected -> when (val failure = result.failure) {
            is IdeHostCompatibilityFailure.Mismatch -> failure.mismatch
            else -> fail("expected mismatch, got $failure")
        }
    }

    private fun rejectedCapabilityMismatch(raw: List<String>): IdeHostCompatibilityFailure {
        val mismatch = rejectedMismatch(expected.copy(capabilities = raw))
            as IdeHostCompatibilityMismatch.Capabilities
        assertEquals(policy.supportedCompatibility.capabilities, mismatch.expected)
        assertEquals(raw, mismatch.observed.capabilities.map { it.operation.id.value })
        return IdeHostCompatibilityFailure.Mismatch(mismatch)
    }

    private fun IdeHostCompatibilityMismatch.identityValues(): Pair<String, String> = when (this) {
        is IdeHostCompatibilityMismatch.IdeBuild -> expected.value to observed.value
        is IdeHostCompatibilityMismatch.KotlinPluginBuild -> expected.value to observed.value
        is IdeHostCompatibilityMismatch.KastPluginVersion -> expected.value to observed.value
        is IdeHostCompatibilityMismatch.RuntimeProtocol -> expected.value to observed.value
        is IdeHostCompatibilityMismatch.OperationRegistry -> expected.value to observed.value
        is IdeHostCompatibilityMismatch.WireSchema -> expected.value to observed.value
        is IdeHostCompatibilityMismatch.Capabilities -> fail("expected an identity mismatch")
    }

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
