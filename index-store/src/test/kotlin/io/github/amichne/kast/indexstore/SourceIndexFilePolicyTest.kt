package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.client.WorkspaceRelativePath
import io.github.amichne.kast.api.contract.query.SemanticGraphPath
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeText
import org.junit.jupiter.api.io.TempDir

class SourceIndexFilePolicyTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `accepts Kotlin source files and rejects Kotlin scripts`() {
        val policy = SourceIndexFilePolicy.forWorkspace(Path.of("/workspace"))
        assertTrue(policy.isEligible(Path.of("/workspace/src/main/kotlin/Foo.kt")))

        assertFalse(policy.isEligible(Path.of("/workspace/src/main/kotlin/Foo.KT")))
        assertFalse(policy.isEligible(Path.of("/workspace/build.gradle.kts")))
        assertFalse(policy.isEligible(Path.of("/workspace/settings.gradle.kts")))
        assertFalse(policy.isEligible(Path.of("/workspace/script.main.kts")))
    }

    @Test
    fun `rejects generated and IDE output roots`() {
        val policy = SourceIndexFilePolicy.forWorkspace(Path.of("/workspace"))
        for (path in listOf(
            "/workspace/build/generated/Foo.kt",
            "/workspace/plugin/build/distributions/Plugin.kt",
            "/workspace/.gradle/caches/Foo.kt",
            "/workspace/out/production/Foo.kt",
            "/workspace/cli-rs/target/debug/deps/Foo.kt",
            "/workspace/.idea/Foo.kt",
        )) {
            assertFalse(policy.isEligible(Path.of(path)), path)
        }
    }

    @Test
    fun `hard exclusions inspect only workspace-relative components`() {
        val workspace = tempDir.resolve("build/checkout").createDirectories()
        val source = workspace.resolve("src/main/App.kt").also { path ->
            path.parent.createDirectories()
            path.writeText("class App")
        }
        val generated = workspace.resolve("module/build/Generated.kt").also { path ->
            path.parent.createDirectories()
            path.writeText("class Generated")
        }
        val policy = SourceIndexFilePolicy.forWorkspace(workspace)

        assertTrue(policy.isEligible(source))
        assertFalse(policy.isEligible(generated))
    }

    @Test
    fun `canonical workspace policy rejects symlinks that escape the root`() {
        val workspace = tempDir.resolve("workspace").createDirectories()
        val outside = tempDir.resolve("outside").createDirectories()
        outside.resolve("Escaped.kt").writeText("class Escaped")
        val link = workspace.resolve("linked").createSymbolicLinkPointingTo(outside)
        val policy = SourceIndexFilePolicy.forWorkspace(workspace)

        assertNull(policy.sourcePath(link.resolve("Escaped.kt")))
        assertNull(policy.sourcePath(WorkspaceRelativePath.parse(Path.of("linked/Escaped.kt"))))
        assertFalse(policy.isEligible(link.resolve("Escaped.kt")))
    }

    @Test
    fun `workspace source proof retains canonical absolute and relative paths`() {
        val workspace = tempDir.resolve("workspace-proof").createDirectories()
        val source = workspace.resolve("src/main/App.kt").also { path ->
            path.parent.createDirectories()
            path.writeText("class App")
        }

        val proven = requireNotNull(SourceIndexFilePolicy.forWorkspace(workspace).sourcePath(source))

        assertEquals(SemanticGraphPath.parse(source.toRealPath().toString()), proven.absolute)
        assertEquals(WorkspaceRelativePath.parse(Path.of("src/main/App.kt")), proven.relative)
    }

    @Test
    fun `outside root Kotlin file cannot obtain a workspace source proof`() {
        val workspace = tempDir.resolve("workspace-contained").createDirectories()
        val outside = tempDir.resolve("outside-root/Outside.kt").also { path ->
            path.parent.createDirectories()
            path.writeText("class Outside")
        }
        val policy = SourceIndexFilePolicy.forWorkspace(workspace)

        assertNull(policy.sourcePath(outside))
        assertFalse(policy.isEligible(outside))
    }
}
