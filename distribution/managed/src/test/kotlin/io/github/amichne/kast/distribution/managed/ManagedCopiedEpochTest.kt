package io.github.amichne.kast.distribution.managed

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManagedCopiedEpochTest {
    @Test
    fun `epoch removal preserves a different inode that replaces the admitted copied record`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val staged = Files.createDirectory(root.resolve(".install-candidate"))
        val state = Files.createDirectory(staged.resolve("state"))
        val path = Files.writeString(state.resolve("epoch.json"), "admitted copied record")
        val admitted =
            assertInstanceOf(ManagedCopiedEpochAdmission.Admitted::class.java, ManagedCopiedEpoch.admit(staged)).epoch
        Files.move(path, state.resolve("retained-original"))
        Files.writeString(path, "unproven replacement")
        assertEquals(ManagedCopiedEpochRemoval.IDENTITY_REJECTED, admitted.remove())
        assertEquals("unproven replacement", Files.readString(path))
        assertEquals("admitted copied record", Files.readString(state.resolve("retained-original")))
    }
}
