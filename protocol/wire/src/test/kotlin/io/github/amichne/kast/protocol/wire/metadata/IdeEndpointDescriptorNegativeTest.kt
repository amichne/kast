package io.github.amichne.kast.protocol.wire.metadata

import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityField
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityMismatch
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
            Triple(
                candidate.copy(ideBuild = "262.9437.186"),
                IdeHostCompatibilityField.IDE_BUILD,
                candidate.ideBuild to "262.9437.186",
            ),
            Triple(
                candidate.copy(kotlinPluginBuild = "262.9437.186-IJ"),
                IdeHostCompatibilityField.KOTLIN_PLUGIN_BUILD,
                candidate.kotlinPluginBuild to "262.9437.186-IJ",
            ),
            Triple(
                candidate.copy(kastPluginVersion = "0.28.2"),
                IdeHostCompatibilityField.KAST_PLUGIN_VERSION,
                candidate.kastPluginVersion to "0.28.2",
            ),
            Triple(
                candidate.copy(runtimeProtocolIdentity = "kast.ide-hosted.runtime.v2"),
                IdeHostCompatibilityField.RUNTIME_PROTOCOL_IDENTITY,
                candidate.runtimeProtocolIdentity to "kast.ide-hosted.runtime.v2",
            ),
            Triple(
                candidate.copy(operationRegistryDigest = digest('c')),
                IdeHostCompatibilityField.OPERATION_REGISTRY_DIGEST,
                candidate.operationRegistryDigest to digest('c'),
            ),
            Triple(
                candidate.copy(wireSchemaDigest = digest('d')),
                IdeHostCompatibilityField.WIRE_SCHEMA_DIGEST,
                candidate.wireSchemaDigest to digest('d'),
            ),
        ).forEach { (invalid, field, values) ->
            val mismatch = rejectedCompatibilityMismatch(invalid)
            assertEquals(field, mismatch.field)
            assertEquals(values, mismatch.identityValues())
        }
    }

    @Test
    fun `unknown unsupported duplicate missing and reordered capabilities fail closed`() {
        assertRejected(
            candidate.copy(
                capabilities = candidate.capabilities +
                    HostedCapabilityCandidate("other.read", emptyList()),
            ),
            hostedCapabilityFailure(
                HostedCapabilitySetFailure.UnknownOperation(operationId("other.read")),
            ),
        )
        assertRejected(
            candidate.copy(
                capabilities = candidate.capabilities +
                    HostedCapabilityCandidate("a".repeat(97), emptyList()),
            ),
            hostedCapabilityFailure(
                HostedCapabilitySetFailure.MalformedOperationId(
                    io.github.amichne.kast.kernel.PermanentIdentityFailure.TOO_LONG,
                ),
            ),
        )
        assertRejected(
            candidate.copy(capabilities = candidate.capabilities + candidate.capabilities.first()),
            hostedCapabilityFailure(
                HostedCapabilitySetFailure.DuplicateOperation(
                    io.github.amichne.kast.protocol.contract.CanonicalOperation.WORKSPACE_INSPECT,
                ),
            ),
        )
        assertRejected(
            candidate.copy(capabilities = candidate.capabilities.dropLast(1)),
            hostedCapabilityFailure(HostedCapabilitySetFailure.CanonicalProjectionMismatch),
        )
        assertRejected(
            candidate.copy(capabilities = candidate.capabilities.reversed()),
            hostedCapabilityFailure(HostedCapabilitySetFailure.CanonicalProjectionMismatch),
        )
        assertRejected(
            candidate.copy(
                capabilities = candidate.capabilities.map { capability ->
                    if (capability.operationId == "change.plan") {
                        capability.copy(intents = listOf("rename-symbol"))
                    } else {
                        capability
                    }
                },
            ),
            hostedCapabilityFailure(
                HostedCapabilitySetFailure.UnsupportedIntent(
                    io.github.amichne.kast.protocol.contract.CanonicalOperation.CHANGE_PLAN,
                ),
            ),
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

    private fun rejectedCompatibilityMismatch(
        invalid: IdeEndpointDescriptorCandidate,
    ): IdeHostCompatibilityMismatch {
        val descriptorFailure = when (val admission = IdeEndpointDescriptorV2.create(invalid, policy)) {
            is IdeEndpointDescriptorAdmission.Admitted ->
                fail<Nothing>("endpoint unexpectedly admitted")
            is IdeEndpointDescriptorAdmission.Rejected -> admission.failure
        }
        val compatibilityFailure = when (descriptorFailure) {
            is IdeEndpointDescriptorFailure.CompatibilityRejected -> descriptorFailure.failure
            else -> fail<Nothing>("expected compatibility rejection, got $descriptorFailure")
        }
        return when (compatibilityFailure) {
            is IdeHostCompatibilityFailure.Mismatch -> compatibilityFailure.mismatch
            else -> fail("expected compatibility mismatch, got $compatibilityFailure")
        }
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

    private fun hostedCapabilityFailure(
        failure: HostedCapabilitySetFailure,
    ) = IdeEndpointDescriptorFailure.HostedCapabilitiesRejected(failure)

    private fun digest(character: Char): String = "sha256:" + character.toString().repeat(64)

    private fun operationId(raw: String): OperationId = when (val parsed = OperationId.parse(raw)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> fail("invalid test operation identity: ${parsed.failure}")
    }
}
