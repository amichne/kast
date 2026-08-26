package io.github.amichne.kast.protocol.wire.metadata

import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.IdeHostCapability
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityField
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityIdentityField
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilitySyntaxFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class IdeEndpointDescriptorNegativeTest {
    private val policy = fixtureEndpointPolicy()
    private val candidate = fixtureEndpointCandidate()

    @Test
    fun `malformed unknown missing duplicate and noncanonical documents fail closed`() {
        assertRejected("{", IdeEndpointDescriptorFailure.MalformedDocument)
        assertRejected(
            candidateDocument().replaceFirst("{", "{\"unknown\":true,"),
            IdeEndpointDescriptorFailure.MalformedDocument,
        )
        assertRejected(
            candidateDocument().replace("\"runtimeEpoch\":0,", ""),
            IdeEndpointDescriptorFailure.MalformedDocument,
        )
        assertRejected(
            candidateDocument().replace(
                "\"processId\":1,",
                "\"processId\":1,\"processId\":1,",
            ),
            IdeEndpointDescriptorFailure.NonCanonicalDocument,
        )
        assertRejected(
            candidateDocument().replaceFirst("{", "{ "),
            IdeEndpointDescriptorFailure.NonCanonicalDocument,
        )
    }

    @Test
    fun `stale schema wrong host and wrong framing remain distinct failures`() {
        assertRejected(
            candidate.copy(schema = "kast.runtime.endpoint.v1"),
            IdeEndpointDescriptorFailure.UnsupportedSchema,
        )
        assertRejected(
            candidate.copy(hostKind = "ISOLATED_INDEXER"),
            IdeEndpointDescriptorFailure.UnsupportedHostKind,
        )
        assertRejected(
            candidate.copy(framing = "newline-json-v1"),
            IdeEndpointDescriptorFailure.UnsupportedFraming,
        )
    }

    @Test
    fun `invalid root socket process and epoch values remain field-specific failures`() {
        listOf(
            candidate.copy(canonicalRoot = " ") to
                IdeEndpointDescriptorFailure.InvalidCanonicalRoot(
                    IdeEndpointPathFailure.BLANK,
                ),
            candidate.copy(canonicalRoot = "workspace/kast") to
                IdeEndpointDescriptorFailure.InvalidCanonicalRoot(
                    IdeEndpointPathFailure.NOT_ABSOLUTE,
                ),
            candidate.copy(canonicalRoot = "/workspace/../kast") to
                IdeEndpointDescriptorFailure.InvalidCanonicalRoot(
                    IdeEndpointPathFailure.NOT_NORMALIZED,
                ),
            candidate.copy(canonicalRoot = "/workspace/\u0000kast") to
                IdeEndpointDescriptorFailure.InvalidCanonicalRoot(
                    IdeEndpointPathFailure.CONTAINS_NUL,
                ),
            candidate.copy(canonicalRoot = "/" + "é".repeat(2_048)) to
                IdeEndpointDescriptorFailure.InvalidCanonicalRoot(
                    IdeEndpointPathFailure.TOO_LONG,
                ),
            candidate.copy(socketPath = "") to
                IdeEndpointDescriptorFailure.InvalidSocketPath(
                    IdeEndpointPathFailure.BLANK,
                ),
            candidate.copy(socketPath = "/tmp//kast.sock") to
                IdeEndpointDescriptorFailure.InvalidSocketPath(
                    IdeEndpointPathFailure.NOT_NORMALIZED,
                ),
            candidate.copy(socketPath = "/tmp/\u0000kast.sock") to
                IdeEndpointDescriptorFailure.InvalidSocketPath(
                    IdeEndpointPathFailure.CONTAINS_NUL,
                ),
            candidate.copy(socketPath = "/" + "a".repeat(103)) to
                IdeEndpointDescriptorFailure.InvalidSocketPath(
                    IdeEndpointPathFailure.TOO_LONG,
                ),
            candidate.copy(socketPath = "/" + "é".repeat(52)) to
                IdeEndpointDescriptorFailure.InvalidSocketPath(
                    IdeEndpointPathFailure.TOO_LONG,
                ),
            candidate.copy(processId = 0) to
                IdeEndpointDescriptorFailure.InvalidProcessId(
                    IdeProcessIdFailure.NOT_POSITIVE,
                ),
            candidate.copy(runtimeEpoch = -1) to
                IdeEndpointDescriptorFailure.InvalidRuntimeEpoch(
                    IdeRuntimeEpochFailure.NEGATIVE,
                ),
        ).forEach { (invalid, failure) -> assertRejected(invalid, failure) }
    }

    @Test
    fun `compatibility identity mismatches retain the exact rejected field`() {
        listOf(
            candidate.copy(ideBuild = "262.9437.186") to
                IdeHostCompatibilityIdentityField.IDE_BUILD,
            candidate.copy(kotlinPluginBuild = "262.9437.186-IJ") to
                IdeHostCompatibilityIdentityField.KOTLIN_PLUGIN_BUILD,
            candidate.copy(kastPluginVersion = "0.28.2") to
                IdeHostCompatibilityIdentityField.KAST_PLUGIN_VERSION,
            candidate.copy(runtimeProtocolIdentity = "kast.ide-hosted.runtime.v2") to
                IdeHostCompatibilityIdentityField.RUNTIME_PROTOCOL_IDENTITY,
            candidate.copy(operationRegistryDigest = digest('c')) to
                IdeHostCompatibilityIdentityField.OPERATION_REGISTRY_DIGEST,
            candidate.copy(wireSchemaDigest = digest('d')) to
                IdeHostCompatibilityIdentityField.WIRE_SCHEMA_DIGEST,
        ).forEach { (invalid, field) ->
            assertRejected(
                invalid,
                IdeEndpointDescriptorFailure.CompatibilityRejected(
                    IdeHostCompatibilityFailure.Mismatch(field),
                ),
            )
        }
    }

    @Test
    fun `unknown unsupported duplicate missing and reordered capabilities fail closed`() {
        assertRejected(
            candidate.copy(capabilities = candidate.capabilities + "other.read"),
            compatibilityFailure(
                IdeHostCompatibilityFailure.UnknownCapability(operationId("other.read")),
            ),
        )
        assertRejected(
            candidate.copy(capabilities = candidate.capabilities + "diagnostic.check"),
            compatibilityFailure(
                IdeHostCompatibilityFailure.UnsupportedCapability(
                    CanonicalOperation.DIAGNOSTIC_CHECK,
                ),
            ),
        )
        assertRejected(
            candidate.copy(capabilities = candidate.capabilities + "a".repeat(97)),
            compatibilityFailure(
                IdeHostCompatibilityFailure.Malformed(
                    IdeHostCompatibilityField.CAPABILITIES,
                    IdeHostCompatibilitySyntaxFailure.TOO_LONG,
                ),
            ),
        )
        assertRejected(
            candidate.copy(capabilities = candidate.capabilities + "workspace.inspect"),
            compatibilityFailure(
                IdeHostCompatibilityFailure.DuplicateCapability(
                    IdeHostCapability.WORKSPACE_INSPECT,
                ),
            ),
        )
        assertRejected(
            candidate.copy(capabilities = candidate.capabilities.dropLast(1)),
            compatibilityFailure(IdeHostCompatibilityFailure.CapabilitySetMismatch),
        )
        assertRejected(
            candidate.copy(capabilities = candidate.capabilities.reversed()),
            compatibilityFailure(IdeHostCompatibilityFailure.CapabilitySetMismatch),
        )
    }

    private fun candidateDocument(): String = IdeEndpointDescriptorV2.create(
        candidate,
        policy,
    ).admittedDescriptor().encode().document

    private fun assertRejected(
        invalid: IdeEndpointDescriptorCandidate,
        expected: IdeEndpointDescriptorFailure,
    ) {
        when (val admission = IdeEndpointDescriptorV2.create(invalid, policy)) {
            is IdeEndpointDescriptorAdmission.Admitted -> fail("endpoint unexpectedly admitted")
            is IdeEndpointDescriptorAdmission.Rejected -> assertEquals(expected, admission.failure)
        }
    }

    private fun assertRejected(raw: String, expected: IdeEndpointDescriptorFailure) {
        when (val admission = IdeEndpointDescriptorV2.admit(raw, policy)) {
            is IdeEndpointDescriptorAdmission.Admitted -> fail("endpoint unexpectedly admitted")
            is IdeEndpointDescriptorAdmission.Rejected -> assertEquals(expected, admission.failure)
        }
    }

    private fun compatibilityFailure(
        failure: IdeHostCompatibilityFailure,
    ) = IdeEndpointDescriptorFailure.CompatibilityRejected(failure)

    private fun digest(character: Char): String = "sha256:" + character.toString().repeat(64)

    private fun operationId(raw: String): OperationId = when (val parsed = OperationId.parse(raw)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> fail("invalid test operation identity: ${parsed.failure}")
    }
}
