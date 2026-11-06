package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class HostedCompatibilityRequirementsTest {
    @Test
    fun `reviewed platform lines preserve actual identities`() {
        val contract = contract()
        val required = required(contract)
        for (build in listOf("262.11879.100", "263.7307.200")) {
            val document = HostedCompatibilityDocument(build, "$build-IJ", "1.2.3", contract)
            val actual =
                assertInstanceOf<IdeHostCompatibilityAdmission.Admitted>(required.admit(document)).compatibility
            assertEquals(build, actual.ideBuild.value)
            assertEquals("$build-IJ", actual.kotlinPluginBuild.value)
            assertEquals("1.2.3", actual.kastPluginVersion.value)
        }
    }

    @Test
    fun `reviewed platform admission preserves exact contract rejection`() {
        val contract = contract()
        val required = required(contract)
        for (build in listOf("262.11879.100", "263.7307.200")) {
            val changed = contract.copy(wireSchemaDigest = "sha256:" + "c".repeat(64))
            val document = HostedCompatibilityDocument(build, "$build-IJ", "1.2.3", changed)
            val failure = assertInstanceOf<IdeHostCompatibilityAdmission.Rejected>(required.admit(document)).failure
            assertEquals(
                IdeHostCompatibilityField.WIRE_SCHEMA_DIGEST,
                assertInstanceOf<IdeHostCompatibilityFailure.Mismatch>(failure).mismatch.field,
            )
        }
    }

    @Test
    fun `unreviewed platforms reject with their actual identities`() {
        val contract = contract()
        val required = required(contract)
        for (line in listOf("261", "264")) {
            val document = HostedCompatibilityDocument("$line.1.1", "$line.1.1-IJ", "1.2.3", contract)
            val failure = assertInstanceOf<IdeHostCompatibilityAdmission.Rejected>(required.admit(document)).failure
            val mismatch =
                assertInstanceOf<IdeHostCompatibilityMismatch.IdeBuild>(
                    assertInstanceOf<IdeHostCompatibilityFailure.Mismatch>(failure).mismatch
                )
            assertEquals("262.10315.125", mismatch.expected.value)
            assertEquals("$line.1.1", mismatch.observed.value)
        }
    }

    @Test
    fun `different actual IDEA and Kotlin lines preserve the rejected pair`() {
        val contract = contract()
        val mixed = HostedCompatibilityDocument("263.7307.200", "262.11879.100-IJ", "1.2.3", contract)
        val failure = assertInstanceOf<IdeHostCompatibilityAdmission.Rejected>(required(contract).admit(mixed)).failure
        val mismatch =
            assertInstanceOf<IdeHostCompatibilityMismatch.KotlinPluginBuild>(
                assertInstanceOf<IdeHostCompatibilityFailure.Mismatch>(failure).mismatch
            )
        assertEquals("263.7307.200-IJ", mismatch.expected.value)
        assertEquals("262.11879.100-IJ", mismatch.observed.value)
    }

    @Test
    fun `different expected IDEA and Kotlin lines reject the malformed requirements`() {
        val contract = contract()
        val required = required(contract)
        for (line in listOf("261", "263", "264")) {
            val invalidRequired =
                required.copy(
                    kotlinPluginBuild =
                        assertInstanceOf<Refinement.Refined<KotlinPluginBuildIdentity>>(
                                KotlinPluginBuildIdentity.parse("$line.1.1-IJ")
                            )
                            .value
                )
            val invalid =
                assertInstanceOf<IdeHostCompatibilityAdmission.Rejected>(
                        invalidRequired.admit(HostedCompatibilityDocument("262.1.1", "262.1.1-IJ", "1.2.3", contract))
                    )
                    .failure
            val mismatch =
                assertInstanceOf<IdeHostCompatibilityMismatch.KotlinPluginBuild>(
                    assertInstanceOf<IdeHostCompatibilityFailure.Mismatch>(invalid).mismatch
                )
            assertEquals("262.10315.125-IJ", mismatch.expected.value)
            assertEquals("$line.1.1-IJ", mismatch.observed.value)
        }
    }

    private fun required(contract: HostedContractDocument): HostedCompatibilityRequirements =
        HostedCompatibilityRequirements(
            assertInstanceOf<Refinement.Refined<IdeBuildIdentity>>(IdeBuildIdentity.parse("262.10315.125")).value,
            assertInstanceOf<Refinement.Refined<KotlinPluginBuildIdentity>>(
                    KotlinPluginBuildIdentity.parse("262.10315.125-IJ")
                )
                .value,
            assertInstanceOf<Refinement.Refined<HostedContract>>(contract.admit()).value,
        )

    private fun contract(): HostedContractDocument =
        HostedContractDocument(
            runtimeProtocolIdentity = "kast.ide-hosted.runtime.v3",
            operationRegistryDigest = "sha256:" + "a".repeat(64),
            wireSchemaDigest = "sha256:" + "b".repeat(64),
            capabilities = emptyList(),
        )
}
