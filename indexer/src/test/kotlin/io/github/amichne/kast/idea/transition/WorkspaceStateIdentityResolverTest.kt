package io.github.amichne.kast.idea.transition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class WorkspaceStateIdentityResolverTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `Git commit identity does not affect workspace state identity`() {
        root.resolve("src/App.kt").also { it.parent.createDirectories(); it.writeText("fun app() = 1") }
        root.resolve(".git").createDirectories()
        root.resolve(".git/HEAD").writeText("first")
        val resolver = resolver()
        val first = resolver.resolve()

        root.resolve(".git/HEAD").writeText("second")

        assertEquals(first, resolver.resolve())
    }

    @Test
    fun `dirty and untracked admitted sources affect workspace state identity`() {
        val source = root.resolve("src/App.kt").also { it.parent.createDirectories(); it.writeText("fun app() = 1") }
        val resolver = resolver()
        val clean = resolver.resolve()

        source.writeText("fun app() = 2")
        val dirty = resolver.resolve()
        root.resolve("src/Untracked.kt").writeText("fun untracked() = 3")

        assertNotEquals(clean, dirty)
        assertNotEquals(dirty, resolver.resolve())
    }

    @Test
    fun `build environment and scope affect workspace state identity`() {
        root.resolve("settings.gradle.kts").writeText("rootProject.name = \"demo\"")
        val base = resolver(environment = "classpath-a", scope = "scope-a").resolve()

        assertNotEquals(base, resolver(environment = "classpath-b", scope = "scope-a").resolve())
        assertNotEquals(base, resolver(environment = "classpath-a", scope = "scope-b").resolve())
    }

    private fun resolver(
        environment: String = "classpath",
        scope: String = "scope",
    ): WorkspaceStateIdentityResolver = WorkspaceStateIdentityResolver(
        workspaceRoot = root,
        semanticEnvironmentIdentity = { environment },
        indexingScopeIdentity = { scope },
    )
}
