package io.github.amichne.kast.api.client

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class WorkspacePathPolicyTest {
    @Test
    fun `hard output directories are excluded by path component`() {
        for (path in listOf(
            "build/generated/Foo.kt",
            ".gradle/cache/Foo.kt",
            "out/classes/Foo.kt",
            "cli-rs/target/debug/deps/Foo.kt",
            ".idea/Foo.kt",
        )) {
            assertTrue(WorkspacePathPolicy.isHardExcluded(Path.of(path)), path)
        }
        assertFalse(WorkspacePathPolicy.isHardExcluded(Path.of("src/main/kotlin/Build.kt")))
    }
}
