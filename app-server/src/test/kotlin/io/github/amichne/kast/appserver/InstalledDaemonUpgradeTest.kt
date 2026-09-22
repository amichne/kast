package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstalledDaemonUpgradeTest {
    @Test
    fun `marker observation rejects links without following their target`(@TempDir root: Path) {
        val marker = root.resolve("service.plist")
        assertEquals(UpgradeServiceMarkers.Absent, observeUpgradeMarker(marker))
        Files.createSymbolicLink(marker, root.resolve("foreign"))
        assertEquals(UpgradeServiceMarkers.Rejected, observeUpgradeMarker(marker))
        Files.delete(marker)
        Files.writeString(marker, "owned")
        assertEquals(UpgradeServiceMarkers.Retained, observeUpgradeMarker(marker))
    }

    @Test
    fun `only absent launchd and absent service evidence prove no daemon`() {
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
        }
    }
}
