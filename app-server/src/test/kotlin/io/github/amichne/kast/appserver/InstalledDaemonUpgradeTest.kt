package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstalledDaemonUpgradeTest {
    @Test
    fun `absent launchd with only retained service configuration permits normal retirement`(@TempDir root: Path) {
        val command = command(root)
        val receipt = Files.createDirectories(command.stateDirectory).resolve("service.plist")
        val document = BrokerLaunchdServiceDocument.render(command)
        Files.writeString(receipt, document)

        assertEquals(
            UpgradePresence.Absent,
            classifyUpgradePresence(BrokerLifecycleObservation.ABSENT, observeUpgradeMarkers(command)),
        )
        assertEquals(document, Files.readString(receipt))
    }

    @Test
    fun `missing prior executable rejects before service observation`() {
        assertEquals(
            InstalledUpgradePreparation.Rejected(
                InstalledUpgradeRejection.Command(PersistentBrokerServiceFailure.KAST_EXECUTABLE_UNAVAILABLE)
            ),
            InstalledDaemonUpgrade.prepare(
                kast = Path.of("/unobserved/kast"),
                userHome = Path.of("/unobserved/home"),
                environment = mapOf("JAVA_HOME" to "\u0000"),
                candidate = "a".repeat(64),
            ),
        )
    }

    @Test
    fun `marker observation rejects links without following their target`(@TempDir root: Path) {
        val marker = root.resolve("service.plist")
        assertEquals(UpgradeMarkerPresence.Absent, observeUpgradeMarker(marker))
        Files.createSymbolicLink(marker, root.resolve("foreign"))
        assertEquals(UpgradeMarkerPresence.Rejected, observeUpgradeMarker(marker))
        Files.delete(marker)
        Files.writeString(marker, "owned")
        assertEquals(UpgradeMarkerPresence.Retained, observeUpgradeMarker(marker))
    }

    @Test
    fun `launchd and runtime evidence preserve service presence qualifications`() {
        assertEquals(
            UpgradePresence.Absent,
            classifyUpgradePresence(BrokerLifecycleObservation.ABSENT, UpgradeServiceMarkers.Absent),
        )
        assertEquals(
            UpgradePresence.Rejected(InstalledUpgradeRejection.RetainedServiceEvidence),
            classifyUpgradePresence(BrokerLifecycleObservation.ABSENT, UpgradeServiceMarkers.Retained),
        )
        assertEquals(
            UpgradePresence.Active,
            classifyUpgradePresence(BrokerLifecycleObservation.ALIVE, UpgradeServiceMarkers.Absent),
        )
        assertEquals(
            UpgradePresence.Active,
            classifyUpgradePresence(BrokerLifecycleObservation.ALIVE, UpgradeServiceMarkers.ConfigurationOnly),
        )
        assertEquals(
            UpgradePresence.Rejected(InstalledUpgradeRejection.ServiceMarkersRejected),
            classifyUpgradePresence(BrokerLifecycleObservation.ABSENT, UpgradeServiceMarkers.Rejected),
        )
        for ((outcome, failure) in
            listOf(
                BrokerLifecycleObservation.REJECTED to InstalledUpgradeLifecycleFailure.REJECTED,
                BrokerLifecycleObservation.INTERRUPTED to InstalledUpgradeLifecycleFailure.INTERRUPTED,
                BrokerLifecycleObservation.TIMED_OUT to InstalledUpgradeLifecycleFailure.TIMED_OUT,
            )) {
            assertEquals(
                UpgradePresence.Rejected(InstalledUpgradeRejection.Lifecycle(failure)),
                classifyUpgradePresence(outcome, UpgradeServiceMarkers.Absent),
            )
            assertEquals(
                UpgradePresence.Rejected(InstalledUpgradeRejection.Lifecycle(failure)),
                classifyUpgradePresence(outcome, UpgradeServiceMarkers.ConfigurationOnly),
            )
        }
    }

    @Test
    fun `retained runtime markers reject absent launchd instead of becoming configuration only`(@TempDir root: Path) {
        val command = command(root)
        Files.createDirectories(command.stateDirectory)
        Files.writeString(command.stateDirectory.resolve("service.plist"), BrokerLaunchdServiceDocument.render(command))
        for (marker in listOf(command.readinessFile, command.publicSocket, ServiceLoginAgent.path(command))) {
            Files.createDirectories(marker.parent)
            Files.writeString(marker, "retained runtime evidence")
            assertEquals(
                UpgradePresence.Rejected(InstalledUpgradeRejection.RetainedServiceEvidence),
                classifyUpgradePresence(BrokerLifecycleObservation.ABSENT, observeUpgradeMarkers(command)),
            )
            assertEquals("retained runtime evidence", Files.readString(marker))
            Files.delete(marker)
        }
        assertEquals(UpgradeServiceMarkers.ConfigurationOnly, observeUpgradeMarkers(command))
    }

    @Test
    fun `configuration links and wrong types remain rejected even without runtime markers`(@TempDir root: Path) {
        val command = command(root)
        val marker = Files.createDirectories(command.stateDirectory).resolve("service.plist")
        Files.createDirectory(marker)
        assertEquals(UpgradeServiceMarkers.Rejected, observeUpgradeMarkers(command))
        Files.delete(marker)
        Files.createSymbolicLink(marker, root.resolve("foreign"))
        assertEquals(UpgradeServiceMarkers.Rejected, observeUpgradeMarkers(command))
        Files.delete(marker)
        assertEquals(UpgradeServiceMarkers.Absent, observeUpgradeMarkers(command))
    }

    private fun command(root: Path): BrokerServiceLaunchCommand {
        val product = Files.createDirectory(root.resolve("product")).toRealPath()
        val kast = executable(Files.createDirectory(product.resolve("bin")).resolve("kast"))
        executable(Files.createDirectories(product.resolve("share/kast/libexec")).resolve("kast-daemon"))
        val home = Files.createDirectory(root.resolve("home")).toRealPath()
        val emptyBin = Files.createDirectory(root.resolve("empty-bin"))
        return (BrokerServiceLaunchCommand.resolveCoordinator(
                kast,
                home,
                mapOf("PATH" to emptyBin.toString(), "KAST_APP_SERVER_PUBLIC_ENDPOINT" to "codex-control") +
                    selectedBrokerJbr(root),
            ) as BrokerServiceLaunchCommandResolution.Resolved)
            .command
    }

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path
    }
}
