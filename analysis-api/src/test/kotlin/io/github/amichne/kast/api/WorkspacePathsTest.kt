package io.github.amichne.kast.api.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

class WorkspacePathsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `kast install root uses local share kast`() {
        assertEquals(defaultInstallRootPath(), kastInstallRoot())
    }

    @Test
    fun `kast data root follows explicit environment authority`() {
        val installRoot = tempDir.resolve("install-root")
        val dataRoot = tempDir.resolve("generation-data")
        val env = mapOf(kastDataHomeEnv to dataRoot.toString())

        assertEquals(
            dataRoot.toAbsolutePath().normalize(),
            kastDataRoot(env::get, installRoot),
        )
    }

    @Test
    fun `kast data root falls back to install state`() {
        val installRoot = tempDir.resolve("install-root")

        assertEquals(
            installRoot.resolve("state").toAbsolutePath().normalize(),
            kastDataRoot(emptyMap<String, String>()::get, installRoot),
        )
    }

    @Test
    fun allPathsResolveFromConfigOnly() {
        val installRoot = Path.of(System.getProperty("user.home"))
            .resolve(".local/share/kast")
            .toAbsolutePath()
            .normalize()
        val binDir = Path.of(System.getProperty("user.home")).resolve(".local/bin").toAbsolutePath().normalize()
        val cacheDir = Path.of(System.getProperty("user.home")).resolve(".cache/kast").toAbsolutePath().normalize()
        val logsDir = Path.of(System.getProperty("user.home")).resolve(".local/state/kast/logs").toAbsolutePath().normalize()
        val runtimeDir = installRoot.resolve("state/runtime")
        val defaults = KastConfig.defaults()

        assertEquals(runtimeDir.resolve("daemons"), defaultDescriptorDirectory())
        assertEquals(installRoot.toString(), defaults.paths.installRoot.value)
        assertEquals(binDir.toString(), defaults.paths.binDir.value)
        assertEquals(installRoot.resolve("current/lib").toString(), defaults.paths.libDir.value)
        assertEquals(cacheDir.toString(), defaults.paths.cacheDir.value)
        assertEquals(logsDir.toString(), defaults.paths.logsDir.value)
        assertEquals(runtimeDir.toString(), defaults.paths.runtimeDir.value)
        assertEquals(runtimeDir.resolve("daemons").toString(), defaults.paths.descriptorDir.value)
        assertEquals(runtimeDir.toString(), defaults.paths.socketDir.value)
    }

    @Test
    fun `workspace data directory uses install root git worktree path for git remotes`() {
        val installRoot = tempDir.resolve("install-root")
        val workspaceRoot = tempDir.resolve("workspace")
        val gitDir = tempDir.resolve("main.git").resolve("worktrees").resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            installRoot = { installRoot },
            gitWorkspaceResolver = {
                GitWorkspace(
                    toplevel = workspaceRoot,
                    commonDir = tempDir.resolve("main.git"),
                    gitDir = gitDir,
                    remote = GitRemote(host = "github.com", owner = "amichne", repo = "kast"),
                )
            },
        )
        val worktreeHash = gitWorktreeHash(workspaceRoot, gitDir)

        assertEquals(
            installRoot.resolve("state/workspaces/git/github.com/amichne/kast/worktrees/workspace--$worktreeHash"),
            resolver.workspaceDataDirectory(workspaceRoot),
        )
    }

    @Test
    fun `workspace data directory honors generation data root independently of install root`() {
        val installRoot = tempDir.resolve("install-root")
        val dataRoot = tempDir.resolve("state/generation-a/data")
        val workspaceRoot = tempDir.resolve("workspace")
        val gitDir = tempDir.resolve("main.git/worktrees/workspace")
        val resolver = WorkspaceDirectoryResolver(
            installRoot = { installRoot },
            dataRoot = { dataRoot },
            gitWorkspaceResolver = {
                GitWorkspace(
                    toplevel = workspaceRoot,
                    commonDir = tempDir.resolve("main.git"),
                    gitDir = gitDir,
                    remote = GitRemote(host = "github.com", owner = "amichne", repo = "kast"),
                )
            },
        )

        assertTrue(!resolver.workspaceDatabasePath(workspaceRoot).startsWith(installRoot))
        assertTrue(resolver.workspaceDatabasePath(workspaceRoot).startsWith(dataRoot.resolve("workspaces")))
    }

    @Test
    fun `workspace data directory isolates sibling git worktrees from the same remote`() {
        val installRoot = tempDir.resolve("install-root")
        val commonDir = tempDir.resolve("main.git")
        val firstRoot = tempDir.resolve("kast")
        val secondRoot = tempDir.resolve("kast-feature")
        val remote = GitRemote(host = "github.com", owner = "amichne", repo = "kast")
        val resolver = WorkspaceDirectoryResolver(
            installRoot = { installRoot },
            gitWorkspaceResolver = { root ->
                when (root.toAbsolutePath().normalize()) {
                    firstRoot.toAbsolutePath().normalize() -> GitWorkspace(
                        toplevel = firstRoot,
                        commonDir = commonDir,
                        gitDir = commonDir.resolve("worktrees/kast"),
                        remote = remote,
                    )
                    secondRoot.toAbsolutePath().normalize() -> GitWorkspace(
                        toplevel = secondRoot,
                        commonDir = commonDir,
                        gitDir = commonDir.resolve("worktrees/kast-feature"),
                        remote = remote,
                    )
                    else -> null
                }
            },
        )

        val first = resolver.workspaceDataDirectory(firstRoot)
        val second = resolver.workspaceDataDirectory(secondRoot)

        assertTrue(first.startsWith(installRoot.resolve("state/workspaces/git/github.com/amichne/kast/worktrees")))
        assertTrue(second.startsWith(installRoot.resolve("state/workspaces/git/github.com/amichne/kast/worktrees")))
        assertTrue(first != second, "sibling worktrees should not share workspace data: first=$first second=$second")
        assertEquals(first, resolver.workspaceCacheDirectory(firstRoot).parent)
        assertEquals(second.resolve("cache/source-index.db"), resolver.workspaceDatabasePath(secondRoot))
    }

    @Test
    fun `workspace data directory supports git worktrees without parseable origin`() {
        val installRoot = tempDir.resolve("install-root")
        val workspaceRoot = tempDir.resolve("workspace")
        val commonDir = tempDir.resolve("main.git")
        val gitDir = commonDir.resolve("worktrees/workspace")
        val resolver = WorkspaceDirectoryResolver(
            installRoot = { installRoot },
            gitWorkspaceResolver = {
                GitWorkspace(
                    toplevel = workspaceRoot,
                    commonDir = commonDir,
                    gitDir = gitDir,
                    remote = null,
                )
            },
        )

        assertEquals(
            installRoot.resolve("state/workspaces/git/local/${gitCommonDirHash(commonDir)}/worktrees/workspace--${gitWorktreeHash(workspaceRoot, gitDir)}"),
            resolver.workspaceDataDirectory(workspaceRoot),
        )
    }

    @Nested
    inner class KastConfigHomeTest {
        @Test
        fun `resolves config home env when set`() {
            val configHome = tempDir.resolve("kast-config")
            val env = mapOf(kastConfigHomeEnv to configHome.toString())
            val result = kastConfigHome(env::get)
            assertEquals(configHome.toAbsolutePath().normalize(), result)
        }

        @Test
        fun `falls back to home dot config kast when env var is absent`() {
            val env = emptyMap<String, String>()
            val result = kastConfigHome(env::get)
            assertEquals(defaultConfigHome(), result)
        }
    }

    @Nested
    inner class DefaultDescriptorDirectoryTest {
        @Test
        fun `resolves to descriptor directory from config defaults`() {
            val result = defaultDescriptorDirectory()
            assertEquals(
                defaultInstallRootPath().resolve("state/runtime/daemons"),
                result,
            )
        }
    }

    @Nested
    inner class WorkspaceRuntimePathTest {
        @Test
        fun `default socket path stays short for long workspace data directories`() {
            val workspaceRoot = Path(
                "/private/var/folders/test-root",
                "nested".repeat(12),
                "workspace".repeat(8),
            )

            val socketPath = defaultSocketPath(workspaceRoot)
            assertTrue(socketPath.toString().length < 108)
        }

        @Test
        fun localWorkspaceDatabasePathUsesIsolatedJunitConfigHomeByDefault() {
            val workspaceRoot = tempDir.resolve("workspace")
            val userConfigHome = defaultConfigHome()
            val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()

            val databasePath = workspaceDatabasePath(workspaceRoot)

            assertTrue(
                databasePath.startsWith(normalizedWorkspaceRoot),
                "databasePath=$databasePath workspaceRoot=$normalizedWorkspaceRoot",
            )
            assertTrue(
                !databasePath.startsWith(userConfigHome),
                "databasePath=$databasePath userConfigHome=$userConfigHome",
            )
        }
    }

    @Nested
    inner class WorkspaceIdentityTest {
        @Test
        fun `workspace identity keeps index and socket paths isolated by workspace root`() {
            val resolver = WorkspaceDirectoryResolver(
                installRoot = { tempDir.resolve("install-root") },
                gitWorkspaceResolver = { null },
                gitRemoteResolver = { null },
            )
            val first = resolver.workspaceIdentity(tempDir.resolve("first-workspace"))
            val second = resolver.workspaceIdentity(tempDir.resolve("second-workspace"))

            assertNotEquals(first.workspaceId, second.workspaceId)
            assertNotEquals(first.sourceIndexDatabasePath, second.sourceIndexDatabasePath)
            assertNotEquals(first.defaultSocketPath, second.defaultSocketPath)
        }

        @Test
        fun `workspace identity containment rejects sibling prefix paths`() {
            val workspaceRoot = Files.createDirectories(tempDir.resolve("repo"))
            val siblingRoot = Files.createDirectories(tempDir.resolve("repo-other"))
            val identity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot)

            assertTrue(identity.contains(workspaceRoot.resolve("src/main/kotlin/App.kt")))
            assertTrue(!identity.contains(siblingRoot.resolve("src/main/kotlin/App.kt")))
        }

        @Test
        fun `workspace identity records nearest Gradle settings root`() {
            val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
            val moduleRoot = Files.createDirectories(repoRoot.resolve("module"))
            val settingsFile = repoRoot.resolve("settings.gradle.kts")
            Files.writeString(settingsFile, "rootProject.name = \"demo\"\ninclude(\":module\")\n")

            val identity = WorkspaceIdentity.fromWorkspaceRoot(moduleRoot)

            assertEquals(repoRoot.toRealPath(), identity.gradleRoot?.root?.toJavaPath())
            assertEquals(settingsFile.toRealPath(), identity.gradleRoot?.settingsFile?.toJavaPath())
            assertTrue(identity.gradleRoot?.settingsFileHash?.value.orEmpty().isNotBlank())
        }
    }

    private fun defaultConfigHome(): Path = Path.of(System.getProperty("user.home"))
        .resolve(".config")
        .resolve("kast")
        .toAbsolutePath()
        .normalize()

    private fun defaultInstallRootPath(): Path = Path.of(System.getProperty("user.home"))
        .resolve(".local/share/kast")
        .toAbsolutePath()
        .normalize()

    private companion object {
        val kastConfigHomeEnv: String = env("KAST", "CONFIG", "HOME")
        val kastDataHomeEnv: String = env("KAST", "DATA", "HOME")

        fun env(vararg parts: String): String = parts.joinToString("_")
    }
}
