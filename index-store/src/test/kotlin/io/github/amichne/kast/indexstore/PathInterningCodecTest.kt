package io.github.amichne.kast.indexstore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PathInterningCodecTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `workspace paths are stored relative and decoded against current workspace root`() {
        val originalRoot = workspaceRoot.resolve("original").toAbsolutePath().normalize()
        val restoredRoot = workspaceRoot.resolve("restored").toAbsolutePath().normalize()
        val originalPath = originalRoot.resolve("src/main/kotlin/Caller.kt").toString()

        val (relativeDir, filename) = PathInterningCodec(originalRoot).decompose(originalPath)

        assertEquals("src/main/kotlin", relativeDir)
        assertEquals("Caller.kt", filename)
        assertEquals(
            restoredRoot.resolve("src/main/kotlin/Caller.kt").toString(),
            PathInterningCodec(restoredRoot).compose(relativeDir, filename),
        )
    }

    @Test
    fun `workspace directories that look like sentinel prefixes remain workspace relative`() {
        val root = workspaceRoot.toAbsolutePath().normalize()
        val path = root.resolve("__kast_abs__/generated/Collision.kt").toString()

        val (relativeDir, filename) = PathInterningCodec(root).decompose(path)

        assertEquals("__kast_rel__/__kast_abs__/generated", relativeDir)
        assertEquals("Collision.kt", filename)
        assertEquals(path, PathInterningCodec(root).compose(relativeDir, filename))
    }
}
