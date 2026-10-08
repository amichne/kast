package io.github.amichne.kast.distribution.cli

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ManagementUserStateUninstallTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `uninstall removes inactive owned endpoint state`() {
        val fixture = fixture()
        val endpoint = Files.createDirectories(fixture.home.resolve(".kast/ide-hosted/${"a".repeat(32)}"))
        Files.createFile(endpoint.resolve("owner.lock"))
        uninstallInstallation(fixture.root, fixture.home)
        assertFalse(Files.exists(fixture.home.resolve(".kast")))
        assertFalse(Files.exists(fixture.executable))
    }

    @Test
    fun `active endpoint retains cleanup authority and all user state`() {
        val fixture = fixture()
        val endpoint = Files.createDirectories(fixture.home.resolve(".kast/ide-hosted/${"a".repeat(32)}"))
        FileChannel.open(endpoint.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use {
            channel ->
            channel.lock().use {
                val rejection = assertThrows<ManagementRejected> { uninstallInstallation(fixture.root, fixture.home) }
                assertEquals("uninstall-user-state", rejection.stage)
                assertTrue(Files.exists(fixture.executable))
                assertTrue(Files.exists(receiptPath(fixture.root)))
                assertTrue(Files.exists(endpoint.resolve("owner.lock")))
            }
        }
    }

    @Test
    fun `unrecognized user state is protected and cleanup remains retryable`() {
        val fixture = fixture()
        val state = Files.createDirectories(fixture.home.resolve(".kast"))
        val foreign = Files.writeString(state.resolve("notes.txt"), "protected")
        assertThrows<ManagementRejected> { uninstallInstallation(fixture.root, fixture.home) }
        assertEquals("protected", Files.readString(foreign))
        assertTrue(Files.exists(fixture.executable))
        assertTrue(Files.exists(receiptPath(fixture.root)))
    }

    private fun fixture(): Fixture {
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val root = Files.createDirectories(home.resolve(".local/share/kast"))
        val executable = Files.createDirectories(home.resolve(".local/bin")).resolve("kast")
        Files.writeString(executable, "owned executable")
        val receipt =
            ManagementReceipt(
                2,
                root.toString(),
                executable.toString(),
                sha256(executable),
                ReleaseChannel.STABLE,
                emptyList(),
            )
        writeManagementReceipt(root, receipt)
        persistUninstallRetirement(root, receipt)
        return Fixture(home, root, executable)
    }

    private data class Fixture(val home: Path, val root: Path, val executable: Path)
}
