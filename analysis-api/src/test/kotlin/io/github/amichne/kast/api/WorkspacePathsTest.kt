package io.github.amichne.kast.api.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.Path

class WorkspacePathsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `kast install root uses home dot kast`() {
        assertEquals(defaultInstallRootPath(), kastInstallRoot())
    }

    @Test
    fun allPathsResolveFromConfigOnly() {
        val configHome = tempDir.resolve("config-home")
        val hostileEnv = mapOf(
            kastConfigHomeEnv to configHome.toString(),
            xdgConfigHomeEnv to tempDir.resolve("xdg-config").toString(),
            kastHomeEnv to tempDir.resolve("hostile-kast-home").toString(),
            kastInstallRootEnv to tempDir.resolve("hostile-install-root").toString(),
            kastBinDirEnv to tempDir.resolve("hostile-bin").toString(),
            kastHeadlessRuntimeLibsEnv to tempDir.resolve("hostile-runtime-libs").toString(),
        )
        val installRoot = Path.of(System.getProperty("user.home"))
            .resolve(".kast")
            .toAbsolutePath()
            .normalize()
        val defaults = KastConfig.defaults()
        val workspaceRoot = tempDir.resolve("workspace")

        assertEquals(configHome.toAbsolutePath().normalize(), kastConfigHome(hostileEnv::get))
        assertEquals(installRoot, defaultInstallRoot(hostileEnv::get))
        assertEquals(installRoot.resolve("bin"), defaultBinDirectory(hostileEnv::get))
        assertEquals(installRoot.resolve("lib/backends/headless/current/runtime-libs"), defaultHeadlessRuntimeLibsDirectory(hostileEnv::get))
        assertEquals(installRoot.resolve("cache/daemons"), defaultDescriptorDirectory(hostileEnv::get))
        assertEquals(installRoot.resolve("logs"), kastLogDirectory(workspaceRoot, hostileEnv::get))
        assertEquals(installRoot.toString(), defaults.paths.installRoot.value)
        assertEquals(installRoot.resolve("bin").toString(), defaults.paths.binDir.value)
        assertEquals(installRoot.resolve("lib").toString(), defaults.paths.libDir.value)
        assertEquals(installRoot.resolve("cache").toString(), defaults.paths.cacheDir.value)
        assertEquals(installRoot.resolve("logs").toString(), defaults.paths.logsDir.value)
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
            installRoot.resolve("workspaces/git/github.com/amichne/kast/worktrees/workspace--$worktreeHash"),
            resolver.workspaceDataDirectory(workspaceRoot),
        )
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

        assertTrue(first.startsWith(installRoot.resolve("workspaces/git/github.com/amichne/kast/worktrees")))
        assertTrue(second.startsWith(installRoot.resolve("workspaces/git/github.com/amichne/kast/worktrees")))
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
            installRoot.resolve("workspaces/git/local/${gitCommonDirHash(commonDir)}/worktrees/workspace--${gitWorktreeHash(workspaceRoot, gitDir)}"),
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
        fun `ignores xdg config home when config home env is absent`() {
            val env = mapOf(xdgConfigHomeEnv to tempDir.resolve("xdg").toString())
            val result = kastConfigHome(env::get)
            assertEquals(defaultConfigHome(), result)
        }

        @Test
        fun `ignores legacy home env when config home env is absent`() {
            val env = mapOf(kastHomeEnv to tempDir.resolve("kast-home").toString())
            val result = kastConfigHome(env::get)
            assertEquals(defaultConfigHome(), result)
        }

        @Test
        fun `falls back to home dot config kast when env var is absent`() {
            val env = emptyMap<String, String>()
            val result = kastConfigHome(env::get)
            assertEquals(defaultConfigHome(), result)
        }

        @Test
        fun `config home env takes priority over ignored env vars`() {
            val configHome = tempDir.resolve("kast-specific")
            val env = mapOf(
                kastConfigHomeEnv to configHome.toString(),
                xdgConfigHomeEnv to tempDir.resolve("xdg-general").toString(),
                kastHomeEnv to tempDir.resolve("kast-home").toString(),
            )
            val result = kastConfigHome(env::get)
            assertEquals(configHome.toAbsolutePath().normalize(), result)
        }
    }

    @Nested
    inner class ConfigDefaultLayoutTest {
        @Test
        fun `install root ignores legacy home and explicit install root env`() {
            val env = mapOf(
                kastHomeEnv to tempDir.resolve("kast-home").toString(),
                kastInstallRootEnv to tempDir.resolve("install-root").toString(),
            )

            assertEquals(defaultInstallRootPath(), defaultInstallRoot(env::get))
        }

        @Test
        fun `bin directory ignores legacy home and explicit bin env`() {
            val env = mapOf(
                kastHomeEnv to tempDir.resolve("kast-home").toString(),
                kastBinDirEnv to tempDir.resolve("bin").toString(),
            )

            assertEquals(defaultInstallRootPath().resolve("bin"), defaultBinDirectory(env::get))
        }

        @Test
        fun `headless runtime libs resolve from default config lib directory`() {
            val env = mapOf(
                kastHomeEnv to tempDir.resolve("kast-home").toString(),
                kastInstallRootEnv to tempDir.resolve("install-root").toString(),
                kastHeadlessRuntimeLibsEnv to tempDir.resolve("runtime-libs").toString(),
            )

            assertEquals(
                defaultInstallRootPath().resolve("lib/backends/headless/current/runtime-libs"),
                defaultHeadlessRuntimeLibsDirectory(env::get),
            )
        }
    }

    @Nested
    inner class DefaultDescriptorDirectoryTest {
        @Test
        fun `resolves to descriptor directory from config defaults`() {
            val env = mapOf(kastConfigHomeEnv to tempDir.resolve("config").toString())
            val result = defaultDescriptorDirectory(env::get)
            assertEquals(
                defaultInstallRootPath().resolve("cache/daemons"),
                result,
            )
        }
    }

    @Nested
    inner class KastLogDirectoryTest {
        @Test
        fun `resolves to logs directory from config defaults`() {
            val env = mapOf(kastConfigHomeEnv to tempDir.resolve("config").toString())
            val workspaceRoot = tempDir.resolve("workspace")
            val result = kastLogDirectory(workspaceRoot, env::get)

            assertEquals(defaultInstallRootPath().resolve("logs"), result)
        }

        @Test
        fun `different workspace roots share config default logs directory`() {
            val env = mapOf(kastConfigHomeEnv to tempDir.resolve("config").toString())
            val dir1 = kastLogDirectory(tempDir.resolve("workspace-a"), env::get)
            val dir2 = kastLogDirectory(tempDir.resolve("workspace-b"), env::get)
            assertEquals(dir1, dir2)
        }
    }

    @Nested
    inner class LegacyBehaviorTest {
        @Test
        fun `workspace metadata directory resolves to workspace data directory`() {
            val workspaceRoot = tempDir.resolve("workspace").toAbsolutePath().normalize()
            val env = mapOf(kastConfigHomeEnv to tempDir.resolve("config").toString())
            assertEquals(
                workspaceDataDirectory(workspaceRoot, env::get),
                workspaceMetadataDirectory(workspaceRoot, env::get),
            )
        }

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

    private fun defaultConfigHome(): Path = Path.of(System.getProperty("user.home"))
        .resolve(".config")
        .resolve("kast")
        .toAbsolutePath()
        .normalize()

    private fun defaultInstallRootPath(): Path = Path.of(System.getProperty("user.home"))
        .resolve(".kast")
        .toAbsolutePath()
        .normalize()

    private companion object {
        val kastConfigHomeEnv: String = env("KAST", "CONFIG", "HOME")
        val xdgConfigHomeEnv: String = env("XDG", "CONFIG", "HOME")
        val kastHomeEnv: String = env("KAST", "HOME")
        val kastInstallRootEnv: String = env("KAST", "INSTALL", "ROOT")
        val kastBinDirEnv: String = env("KAST", "BIN", "DIR")
        val kastHeadlessRuntimeLibsEnv: String = env("KAST", "HEADLESS", "RUNTIME", "LIBS")

        fun env(vararg parts: String): String = parts.joinToString("_")
    }
}
