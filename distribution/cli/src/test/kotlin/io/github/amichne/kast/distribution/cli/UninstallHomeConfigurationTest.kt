package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class UninstallHomeConfigurationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `admitted configuration and checkpoint remove only their exact directory`() {
        val fixture = fixture()
        val events = mutableListOf<UninstallCleanupObservation>()
        val proof = captureUninstallHomeConfiguration(fixture.home, events::add)
        cleanupUninstallHomeConfiguration(fixture.home, proof, events::add)
        assertFalse(Files.exists(fixture.configuration.parent))
        assertEquals("protected", Files.readString(fixture.unrelated))
        assertEquals(
            listOf(
                UninstallCleanupObservation.Started(UninstallArtifact.HOME_CONFIGURATION),
                UninstallCleanupObservation.Completed(UninstallArtifact.HOME_CONFIGURATION),
            ),
            events,
        )
        cleanupUninstallHomeConfiguration(fixture.home, proof, {})
    }

    @Test
    fun `partial removal resumes from the retained identities`() {
        val fixture = fixture()
        val proof = captureUninstallHomeConfiguration(fixture.home, {})
        Files.delete(fixture.configuration)
        cleanupUninstallHomeConfiguration(fixture.home, proof, {})
        assertFalse(Files.exists(fixture.checkpoint))
        assertFalse(Files.exists(fixture.configuration.parent))
    }

    @Test
    fun `changed checkpoint rejects before deleting another captured file`() {
        val fixture = fixture()
        val proof = captureUninstallHomeConfiguration(fixture.home, {})
        Files.writeString(
            fixture.checkpoint,
            managementJson.encodeToString(
                ConnectionConfigurationDocument(
                    1,
                    listOf(SavedConnectionDirectory(HarnessConnection.PI, fixture.home.resolve("another").toString())),
                )
            ),
        )
        val events = mutableListOf<UninstallCleanupObservation>()
        assertThrows<ManagementRejected> { cleanupUninstallHomeConfiguration(fixture.home, proof, events::add) }
        assertEquals(
            UninstallCleanupObservation.HomeConfigurationRejected(HomeConfigurationCleanupFailure.CONTENT_CHANGED),
            events.last(),
        )
        assertTrue(Files.exists(fixture.configuration))
        assertTrue(Files.exists(fixture.checkpoint))
    }

    @Test
    fun `foreign and malformed state reject admission without deletion`() {
        val fixture = fixture()
        val foreign = Files.writeString(fixture.configuration.parent.resolve("foreign"), "protected")
        val events = mutableListOf<UninstallCleanupObservation>()
        assertThrows<ManagementRejected> { captureUninstallHomeConfiguration(fixture.home, events::add) }
        assertEquals(
            UninstallCleanupObservation.HomeConfigurationRejected(HomeConfigurationCleanupFailure.FOREIGN_CONTENT),
            events.last(),
        )
        assertEquals("protected", Files.readString(foreign))
        Files.delete(foreign)
        Files.writeString(fixture.checkpoint, "malformed configuration")
        assertThrows<ManagementRejected> { captureUninstallHomeConfiguration(fixture.home, events::add) }
        assertEquals(
            UninstallCleanupObservation.HomeConfigurationRejected(HomeConfigurationCleanupFailure.DOCUMENT_REJECTED),
            events.last(),
        )
        assertTrue(Files.exists(fixture.configuration))
    }

    @Test
    fun `replaced directory and symlink cannot inherit captured removal proof`() {
        val fixture = fixture()
        val proof = captureUninstallHomeConfiguration(fixture.home, {})
        val original = fixture.configuration.parent.resolveSibling("protected-original")
        Files.move(fixture.configuration.parent, original)
        Files.createSymbolicLink(fixture.configuration.parent, original)
        val events = mutableListOf<UninstallCleanupObservation>()
        assertThrows<ManagementRejected> { cleanupUninstallHomeConfiguration(fixture.home, proof, events::add) }
        assertTrue(Files.exists(original.resolve("config.json")))
        assertEquals(
            UninstallCleanupObservation.HomeConfigurationRejected(HomeConfigurationCleanupFailure.CONTENT_CHANGED),
            events.last(),
        )
    }

    @Test
    fun `empty owned directory is captured and absence never creates configuration`() {
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val absent = captureUninstallHomeConfiguration(home, {})
        cleanupUninstallHomeConfiguration(home, absent, {})
        assertFalse(Files.exists(home.resolve(".config")))
        val directory = Files.createDirectories(home.resolve(".config/kast"))
        val proof = captureUninstallHomeConfiguration(home, {})
        cleanupUninstallHomeConfiguration(home, proof, {})
        assertFalse(Files.exists(directory))
        assertTrue(Files.exists(home.resolve(".config")))
    }

    private fun fixture(): Fixture {
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val configuration = connectionConfigurationPath(home)
        Files.createDirectories(configuration.parent)
        val checkpoint = connectionCheckpointPath(home)
        val document =
            managementJson.encodeToString(
                ConnectionConfigurationDocument(
                    1,
                    listOf(SavedConnectionDirectory(HarnessConnection.PI, home.resolve(".pi/agent").toString())),
                )
            )
        for (path in listOf(configuration, checkpoint)) {
            Files.writeString(path, document)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
        val unrelated = Files.writeString(home.resolve(".config/unrelated"), "protected")
        return Fixture(home, configuration, checkpoint, unrelated)
    }

    private data class Fixture(val home: Path, val configuration: Path, val checkpoint: Path, val unrelated: Path)
}
