package io.github.amichne.kast.idea.transition

import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.idea.AdmittedWorkspaceContentIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
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
        val resolver = resolver(admittedPaths = { listOf(root.resolve("src/App.kt")) })
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

    @Test
    fun `scope ignored source content does not affect workspace state identity`() {
        root.resolve(".kastignore").writeText("ignored/**\n")
        root.resolve("src/App.kt").also { it.parent.createDirectories(); it.writeText("fun app() = 1") }
        val ignored = root.resolve("ignored/Ignored.kt").also {
            it.parent.createDirectories()
            it.writeText("fun ignored() = 1")
        }
        val resolver = resolver(admittedPaths = { listOf(root.resolve("src/App.kt")) })
        val before = resolver.resolve()

        ignored.writeText("fun ignored() = 2")

        assertEquals(before, resolver.resolve())
    }

    private fun resolver(
        environment: String = "classpath",
        scope: String = "scope",
        admittedPaths: () -> Collection<Path> = {
            Files.walk(root).use { paths ->
                paths.filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
                    .toList()
            }
        },
    ): WorkspaceStateIdentityResolver = WorkspaceStateIdentityResolver(
        workspaceRoot = root,
        admittedContentIdentity = {
            AdmittedWorkspaceContentIdentity.hash(
                admittedPaths()
                    .filter(Files::isRegularFile)
                    .sortedBy(Path::toString)
                    .map { path ->
                        "${root.relativize(path).toString().replace('\\', '/')}|" +
                            FileHashing.sha256(Files.readString(path))
                    },
            )
        },
        semanticEnvironmentIdentity = { environment },
        indexingScopeIdentity = { scope },
    )
}
