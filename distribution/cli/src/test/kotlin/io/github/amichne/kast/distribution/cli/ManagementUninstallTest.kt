package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ManagementUninstallTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `missing payload without retirement proof refuses cleanup`() {
        val fixture = retiredUninstallFixture(temporary)
        Files.delete(uninstallJournalPath(fixture.root))
        assertThrows<ManagementRejected> { uninstallInstallation(fixture.root, fixture.home) }
        assertTrue(Files.exists(fixture.executable))
        assertTrue(Files.exists(fixture.extension))
        assertTrue(Files.exists(receiptPath(fixture.root)))
    }

    @Test
    fun `retry after payload retirement removes receipted registrations and executable`() {
        val fixture = retiredUninstallFixture(temporary)
        val events = mutableListOf<UninstallCleanupObservation>()
        uninstallInstallation(fixture.root, fixture.home, observe = events::add)
        assertFalse(Files.exists(fixture.extension))
        assertFalse(Files.exists(fixture.executable))
        assertFalse(Files.exists(receiptPath(fixture.root)))
        assertFalse(Files.exists(fixture.root))
        assertFalse(Files.exists(uninstallJournalPath(fixture.root)))
        assertEquals("unrelated", Files.readString(fixture.sentinel))
        assertTrue(events.contains(UninstallCleanupObservation.Completed(UninstallArtifact.PUBLIC_EXECUTABLE)))
        assertTrue(events.none { it is UninstallCleanupObservation.Rejected })
    }

    @Test
    fun `retry after executable removal finishes receipt cleanup`() {
        val fixture = retiredUninstallFixture(temporary)
        Files.delete(fixture.executable)
        uninstallInstallation(fixture.root, fixture.home)
        assertFalse(Files.exists(fixture.extension))
        assertFalse(Files.exists(receiptPath(fixture.root)))
        assertEquals("unrelated", Files.readString(fixture.sentinel))
    }

    @Test
    fun `terminal journal permits retry after receipt removal`() {
        val fixture = retiredUninstallFixture(temporary)
        val receipt = (readReceipt(fixture.root) as ReceiptRead.Read).receipt
        Files.delete(fixture.extension)
        Files.delete(fixture.executable)
        persistUninstallRetirement(fixture.root, receipt, UninstallRetirementType.EXTERNALS_CLEANED)
        Files.delete(receiptPath(fixture.root))
        uninstallInstallation(fixture.root, fixture.home)
        assertFalse(Files.exists(uninstallJournalPath(fixture.root)))
        assertEquals("unrelated", Files.readString(fixture.sentinel))
    }

    @Test
    fun `terminal journal finishes after managed root was already removed`() {
        val fixture = retiredUninstallFixture(temporary)
        val receipt = (readReceipt(fixture.root) as ReceiptRead.Read).receipt
        Files.delete(fixture.extension)
        Files.delete(fixture.executable)
        persistUninstallRetirement(fixture.root, receipt, UninstallRetirementType.EXTERNALS_CLEANED)
        Files.delete(receiptPath(fixture.root))
        Files.delete(fixture.root)
        uninstallInstallation(fixture.root, fixture.home)
        assertFalse(Files.exists(uninstallJournalPath(fixture.root)))
        assertEquals("unrelated", Files.readString(fixture.sentinel))
    }

    @Test
    fun `replacement root cannot inherit the retired installation cleanup proof`() {
        val fixture = retiredUninstallFixture(temporary)
        Files.move(fixture.root, fixture.root.resolveSibling("protected-original"))
        Files.createDirectory(fixture.root)
        val foreign = Files.writeString(fixture.root.resolve("management.json"), "protected foreign receipt")
        assertThrows<ManagementRejected> { uninstallInstallation(fixture.root, fixture.home) }
        assertEquals("protected foreign receipt", Files.readString(foreign))
        assertTrue(Files.exists(fixture.executable))
        assertTrue(Files.exists(uninstallJournalPath(fixture.root)))
    }

    @Test
    fun `unknown managed root content blocks all removal`() {
        val fixture = retiredUninstallFixture(temporary)
        val unknown = Files.writeString(fixture.root.resolve("foreign"), "protected")
        assertThrows<ManagementRejected> { uninstallInstallation(fixture.root, fixture.home) }
        assertEquals("protected", Files.readString(unknown))
        assertTrue(Files.exists(fixture.executable))
        assertTrue(Files.exists(fixture.extension))
    }

    @Test
    fun `retired payload cleanup removes its admitted home configuration and checkpoint`() {
        val fixture = retiredUninstallFixture(temporary)
        val configuration = connectionConfigurationPath(fixture.home)
        Files.createDirectories(configuration.parent)
        writeConnectionConfiguration(configuration, ConnectionConfiguration.empty())
        writeConnectionConfiguration(connectionCheckpointPath(fixture.home), ConnectionConfiguration.empty())
        val proof = captureUninstallHomeConfiguration(fixture.home, {})
        val journal =
            managementJson.decodeFromString<FixtureRetirementRecord>(
                Files.readString(uninstallJournalPath(fixture.root))
            )
        Files.writeString(
            uninstallJournalPath(fixture.root),
            managementJson.encodeToString(journal.copy(homeConfiguration = proof)),
        )
        uninstallInstallation(fixture.root, fixture.home)
        assertFalse(Files.exists(configuration.parent))
        assertFalse(Files.exists(fixture.executable))
        assertFalse(Files.exists(fixture.root))
        assertEquals("unrelated", Files.readString(fixture.sentinel))
    }

    @Test
    fun `foreign registration retains receipt and public executable for a later retry`() {
        val fixture = retiredUninstallFixture(temporary)
        Files.writeString(fixture.extension, "foreign")
        val events = mutableListOf<UninstallCleanupObservation>()
        val rejected =
            assertThrows<ManagementRejected> {
                uninstallInstallation(fixture.root, fixture.home, observe = events::add)
            }
        assertEquals("uninstall-registrations", rejected.stage)
        assertEquals("foreign", Files.readString(fixture.extension))
        assertTrue(Files.exists(fixture.executable))
        assertTrue(Files.exists(receiptPath(fixture.root)))
        assertTrue(
            events.contains(
                UninstallCleanupObservation.Rejected(
                    UninstallArtifact.PI_REGISTRATION,
                    UninstallCleanupFailure.OWNERSHIP_UNPROVEN,
                )
            )
        )
        Files.writeString(fixture.extension, "owned adapter")
        uninstallInstallation(fixture.root, fixture.home)
        assertFalse(Files.exists(fixture.executable))
    }

    @Test
    fun `foreign executable cannot be removed even after payload retirement`() {
        val fixture = retiredUninstallFixture(temporary)
        Files.writeString(fixture.executable, "foreign")
        assertThrows<ManagementRejected> { uninstallInstallation(fixture.root, fixture.home) }
        assertEquals("foreign", Files.readString(fixture.executable))
        assertTrue(Files.exists(fixture.extension))
        assertTrue(Files.exists(receiptPath(fixture.root)))
    }
}
