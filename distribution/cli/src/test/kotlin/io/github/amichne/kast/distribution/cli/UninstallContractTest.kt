package io.github.amichne.kast.distribution.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.distribution.managed.ManagedRecoveryRemovalProof
import io.github.amichne.kast.distribution.managed.RecoveryRemovalFailure
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UninstallContractTest {
    @Test
    fun `cleanup variants encode the independently required closed shapes`() {
        val observations =
            listOf(
                UninstallCleanupObservation.Started(UninstallArtifact.CONTROL_PAYLOAD),
                UninstallCleanupObservation.Completed(UninstallArtifact.PUBLIC_EXECUTABLE),
                UninstallCleanupObservation.Rejected(
                    UninstallArtifact.MANAGEMENT_RECEIPT,
                    UninstallCleanupFailure.OWNERSHIP_UNPROVEN,
                ),
                UninstallCleanupObservation.UserStateRejected(UserStateCleanupFailure.UNSETTLED_MUTATION),
                UninstallCleanupObservation.RecoveryRejected(RecoveryRemovalFailure.FOREIGN_CONTENT),
                UninstallCleanupObservation.ControlChildRejected(9),
                UninstallCleanupObservation.ControlPayloadRetained(),
            )
        val schema = schema("uninstall-cleanup.schema.json")
        val expected = expected("uninstall-cleanup-expected-shapes.json")
        observations.forEachIndexed { index, observation ->
            val actual = managementJson.encodeToString<UninstallCleanupObservation>(observation)
            assertEquals(expected[index], Json.parseToJsonElement(actual))
            assertTrue(schema.validate(actual, InputFormat.JSON).isEmpty(), actual)
        }
    }

    @Test
    fun `successful child exit cannot encode the rejected child outcome`() {
        val schema = schema("uninstall-cleanup.schema.json")
        expected("uninstall-cleanup-invalid-shapes.json").forEach { invalid ->
            assertTrue(schema.validate(invalid.toString(), InputFormat.JSON).isNotEmpty(), invalid.toString())
        }
    }

    @Test
    fun `retirement journal retains both exact ownership and terminal cleanup proof`() {
        val schema = schema("uninstall-retirement.schema.json")
        val expected = expected("uninstall-retirement-expected-shapes.json")
        UninstallRetirementType.entries.forEachIndexed { index, type ->
            val record =
                UninstallRetirementJournal(
                    "/home/user/.local/share/kast",
                    "/home/user/.local/bin/kast",
                    "a".repeat(64),
                    ReleaseChannel.STABLE,
                    type,
                    InstallationFilesystemIdentity(1, 2, 501),
                    ManagedRecoveryRemovalProof.Absent,
                    InstallationFilesystemIdentity(1, 3, 501),
                    InstalledProductReference(
                        releaseTag = "v1.2.3",
                        publicVersion = "1.2.3",
                        version = "1.2.3",
                        sourceRevision = "0123456789abcdef0123456789abcdef01234567",
                        sourceTree = "89abcdef0123456789abcdef0123456789abcdef",
                        buildRecordSha256 = "b".repeat(64),
                        target = "macos-aarch64",
                        bundleSha256 = "c".repeat(64),
                        qualificationSha256 = "d".repeat(64),
                        runtimeRoot = "/home/user/.local/share/kast-runtimes/runtime",
                        runtimeArchiveSha256 = "e".repeat(64),
                        runtimeManifestSha256 = "f".repeat(64),
                        policy = ProductSelectionPolicy.TrackStable,
                    ),
                )
            val actual = managementJson.encodeToString(record)
            assertEquals(expected[index], Json.parseToJsonElement(actual))
            assertTrue(schema.validate(actual, InputFormat.JSON).isEmpty(), actual)
        }
    }

    @Test
    fun `retirement journal without its product identity is rejected`() {
        val schema = schema("uninstall-retirement.schema.json")
        expected("uninstall-retirement-invalid-shapes.json").forEach { invalid ->
            val raw = invalid.toString()
            assertTrue(schema.validate(raw, InputFormat.JSON).isNotEmpty(), raw)
            val rejected = assertThrows(ManagementRejected::class.java) { decodeUninstallRetirement(raw) }
            assertEquals(
                ManagementRejected("uninstall-retirement", "retirement proof lacks its product identity"),
                rejected,
            )
        }
    }

    private fun schema(name: String) =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(checkNotNull(javaClass.getResource("/management/$name")).readText())

    private fun expected(name: String) =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/management/$name")).readText()).jsonArray
}
