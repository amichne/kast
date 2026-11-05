package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.ManagementBuildBinding
import io.github.amichne.kast.distribution.contract.UpdatePolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class SelectedActiveCompletionTest {
    @Test
    fun `first active completion publishes the captured main build and policy with the installed bytes`(
        @TempDir directory: Path
    ) {
        val fixture = createLifecycleFixture(directory)
        val identity =
            InstalledBuildIdentity("developer-v1.2.3", "1.2.3", "a".repeat(40), "b".repeat(40), "c".repeat(40))
        val relative = "share/kast/build-identity.json"
        val file = fixture.installation.resolve(relative)
        Files.writeString(
            file,
            managementJson.encodeToString(ControlBuildIdentity("1.2.3", identity.buildRevision, identity.sourceTree)),
        )
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        val manifestFile = fixture.installation.resolve("installation.json")
        val manifest = managementJson.decodeFromString<FixtureLifecycleManifest>(Files.readString(manifestFile))
        Files.writeString(
            manifestFile,
            managementJson.encodeToString(
                manifest.copy(
                    payloadFiles = manifest.payloadFiles + BundledPayload(relative, "sha256:" + sha256(file), 420)
                )
            ),
        )
        val binding = directory.resolve("selection.json")
        Files.writeString(
            binding,
            managementJson.encodeToString(ManagementBuildBinding(identity, UpdatePolicy.TrackMain)),
        )
        val publication = ManagementPublication.Selected.admit(fixture.root, binding)
        val result =
            completePublicInstallation(
                fixture.root,
                mapOf("HOME" to fixture.home.toString()),
                ReleaseChannel.DEVELOPER,
                InstallationCompletion.ACTIVATE,
                execution =
                    LifecycleExecution(
                        child = LifecycleChildExecutor { _, _, _ -> error("unexpected activation child") },
                        processes = closedHostProcesses(),
                    ),
                composition = publication,
            )
        assertInstanceOf<InstallationCompletionResult.Published>(result)
        val receipt = assertInstanceOf<ReceiptRead.Read>(readReceipt(fixture.root)).receipt
        assertEquals(identity, receipt.installedBuild)
        assertEquals(UpdatePolicy.TrackMain, receipt.updatePolicy)
        assertEquals(ReleaseChannel.DEVELOPER, receipt.channel)
        assertEquals(
            Files.readString(fixture.installation.resolve("share/kast/libexec/kast-management")),
            Files.readString(Path.of(receipt.executable)),
        )
    }
}
