package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class IdeHostCompatibilityPolicyTest {
    private val baseline = IdeHostCompatibilityCandidate(
        ideBuild = "262.10315.125",
        kotlinPluginBuild = "262.10315.125-IJ",
        kastPluginVersion = "1.2.3",
        runtimeProtocolIdentity = "kast.ide-hosted.runtime.v1",
        operationRegistryDigest = "sha256:" + "1".repeat(64),
        wireSchemaDigest = "sha256:" + "2".repeat(64),
        capabilities = emptyList(),
    )
    private val policy = when (val result = IdeHostCompatibilityPolicy.define(baseline)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> fail("baseline rejected: ${result.failure}")
    }

    @Test
    fun `262 builds are admitted with their observed identities retained`() {
        for (build in listOf("262.1.1", "262.9437.185", "262.10315.125", "262.20000.200")) {
            val candidate = baseline.copy(ideBuild = build, kotlinPluginBuild = "262.20001.201-IJ")
            val admitted = when (val result = policy.admit(candidate)) {
                is IdeHostCompatibilityAdmission.Admitted -> result.compatibility
                is IdeHostCompatibilityAdmission.Rejected -> fail("$build rejected: ${result.failure}")
            }
            assertEquals(candidate.ideBuild, admitted.ideBuild.value)
            assertEquals(candidate.kotlinPluginBuild, admitted.kotlinPluginBuild.value)
            assertEquals(baseline.wireSchemaDigest, admitted.wireSchemaDigest.value)
        }
    }

    @Test
    fun `IDEA and Kotlin builds outside 262 retain typed mismatches`() {
        for (line in listOf("261", "263")) {
            assertMismatch(baseline.copy(ideBuild = "$line.10315.125"), IdeHostCompatibilityField.IDE_BUILD)
            assertMismatch(
                baseline.copy(kotlinPluginBuild = "$line.10315.125-IJ"),
                IdeHostCompatibilityField.KOTLIN_PLUGIN_BUILD,
            )
        }
    }

    @Test
    fun `release line compatibility preserves exact product and protocol checks`() {
        val candidate = baseline.copy(ideBuild = "262.20000.200", kotlinPluginBuild = "262.20000.200-IJ")
        assertMismatch(candidate.copy(kastPluginVersion = "1.2.4"), IdeHostCompatibilityField.KAST_PLUGIN_VERSION)
        assertMismatch(
            candidate.copy(runtimeProtocolIdentity = "kast.ide-hosted.runtime.v2"),
            IdeHostCompatibilityField.RUNTIME_PROTOCOL_IDENTITY,
        )
        assertMismatch(
            candidate.copy(operationRegistryDigest = "sha256:" + "3".repeat(64)),
            IdeHostCompatibilityField.OPERATION_REGISTRY_DIGEST,
        )
        assertMismatch(
            candidate.copy(wireSchemaDigest = "sha256:" + "3".repeat(64)),
            IdeHostCompatibilityField.WIRE_SCHEMA_DIGEST,
        )
        assertInstanceOf(
            IdeHostCompatibilityAdmission.Rejected::class.java,
            policy.admit(candidate.copy(capabilities = listOf("unknown.operation"))),
        )
    }

    @Test
    fun `a release line prefix cannot admit malformed build identities`() {
        for (build in listOf("262", "262.*", "262.bad.1", "2620.1.1")) {
            val rejected = assertInstanceOf(
                IdeHostCompatibilityAdmission.Rejected::class.java,
                policy.admit(baseline.copy(ideBuild = build)),
            )
            assertInstanceOf(IdeHostCompatibilityFailure.Malformed::class.java, rejected.failure)
        }
    }

    private fun assertMismatch(candidate: IdeHostCompatibilityCandidate, field: IdeHostCompatibilityField) {
        val result = assertInstanceOf(IdeHostCompatibilityAdmission.Rejected::class.java, policy.admit(candidate))
        val failure = assertInstanceOf(IdeHostCompatibilityFailure.Mismatch::class.java, result.failure)
        assertEquals(field, failure.mismatch.field)
    }
}
