package io.github.amichne.kast.distribution.managed

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationSnapshotTest {
    @Test
    fun `state copy retains bytes only in the exact staging slot`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val state = Files.createDirectories(root.resolve("installation/state"))
        val staged = Files.createDirectory(root.resolve(".install-candidate"))
        Files.writeString(state.resolve("protected"), "protected recovery evidence")
        assertEquals(
            InstallationSnapshot.Copied,
            copyInstallationSnapshot(state, staged.resolve("state"), InstallationSnapshotKind.STATE),
        )
        assertEquals("protected recovery evidence", Files.readString(staged.resolve("state/protected")))
        assertEquals("protected recovery evidence", Files.readString(state.resolve("protected")))
        val unrelated = Files.createDirectory(root.resolve("foreign"))
        assertEquals(
            InstallationSnapshot.Rejected(InstallationSnapshotFailure.DESTINATION_REJECTED),
            copyInstallationSnapshot(state, unrelated.resolve("state"), InstallationSnapshotKind.STATE),
        )
        assertTrue(Files.notExists(unrelated.resolve("state")))
    }

    @Test
    fun `symbolic source entry rejects before creating a destination or reading the foreign target`(
        @TempDir temporary: Path
    ) {
        val root = temporary.toRealPath()
        val state = Files.createDirectories(root.resolve("installation/state"))
        val staged = Files.createDirectory(root.resolve(".install-candidate"))
        val foreign = Files.writeString(root.resolve("foreign"), "foreign bytes")
        Files.createSymbolicLink(state.resolve("link"), foreign)
        assertEquals(
            InstallationSnapshot.Rejected(InstallationSnapshotFailure.ENTRY_REJECTED),
            copyInstallationSnapshot(state, staged.resolve("state"), InstallationSnapshotKind.STATE),
        )
        assertTrue(Files.notExists(staged.resolve("state")))
        assertEquals("foreign bytes", Files.readString(foreign))
    }
}
