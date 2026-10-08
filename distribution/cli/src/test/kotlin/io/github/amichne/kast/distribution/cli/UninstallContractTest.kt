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
                    UninstallArtifact.PI_REGISTRATION,
                    UninstallCleanupFailure.OWNERSHIP_UNPROVEN,
                ),
                UninstallCleanupObservation.UserStateRejected(UserStateCleanupFailure.UNSETTLED_MUTATION),
                UninstallCleanupObservation.RecoveryRejected(RecoveryRemovalFailure.FOREIGN_CONTENT),
                UninstallCleanupObservation.HostRejected(HostPluginCleanupFailure.OWNERSHIP_UNPROVEN),
                UninstallCleanupObservation.HomeConfigurationRejected(HomeConfigurationCleanupFailure.CONTENT_CHANGED),
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
                    HomeConfigurationRemovalProof.Absent,
                )
            val actual = managementJson.encodeToString(record)
            assertEquals(expected[index], Json.parseToJsonElement(actual))
            assertTrue(schema.validate(actual, InputFormat.JSON).isEmpty(), actual)
        }
    }

    private fun schema(name: String) =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(checkNotNull(javaClass.getResource("/management/$name")).readText())

    private fun expected(name: String) =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/management/$name")).readText()).jsonArray
}
