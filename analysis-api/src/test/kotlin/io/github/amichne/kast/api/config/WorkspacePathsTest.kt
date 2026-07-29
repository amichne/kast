package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.protocol.AnalysisException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
        assertEquals(resolveKastPathDefaults().installRoot, kastInstallRoot())
    }

    @Test
    fun `kast data root follows active CLI receipt authority`() {
        val configuredInstallRoot = tempDir.resolve("configured-install-root")
        val cliInstallRoot = tempDir.resolve("cli-install-root")
        val cliDataRoot = tempDir.resolve("cli-data-root")
        val ignoredDataRoot = tempDir.resolve("ignored-data-root")
        val receipt = cliInstallRoot.resolve("current/receipt.json")
        Files.createDirectories(receipt.parent)
        Files.writeString(
            receipt,
            """
            {
              "tool": "kast",
              "roots": {
                "install": "$cliInstallRoot",
                "bin": "${cliInstallRoot.resolve("current/bin")}",
                "config": "${cliInstallRoot.resolve("current/config")}",
                "data": "$cliDataRoot",
                "cache": "${cliInstallRoot.resolve("state/cache")}",
                "runtime": "${cliInstallRoot.resolve("state/runtime")}",
                "logs": "${cliInstallRoot.resolve("state/logs")}",
                "locks": "$cliInstallRoot"
              },
              "entrypoints": {
                "shim": "${cliInstallRoot.resolve("current/bin/kast")}",
                "activeBinary": "${cliInstallRoot.resolve("current/bin/kast")}"
              }
            }
            """.trimIndent(),
        )
        val env = mapOf(
            kastHomeEnv to cliInstallRoot.toString(),
            kastDataHomeEnv to ignoredDataRoot.toString(),
        )

        assertEquals(
            cliDataRoot.toAbsolutePath().normalize(),
            kastDataRoot(env::get, configuredInstallRoot),
        )
    }

    @Test
    fun `kast data root falls back to CLI install state data`() {
        val installRoot = tempDir.resolve("install-root")

        assertEquals(
            installRoot.resolve("state/data").toAbsolutePath().normalize(),
            kastDataRoot(emptyMap<String, String>()::get, installRoot),
        )
    }

    @Test
    fun `invalid active CLI receipt fails closed`() {
        val installRoot = tempDir.resolve("install-root")
        val receipt = installRoot.resolve("current/receipt.json")
        Files.createDirectories(receipt.parent)

        listOf(
            "{",
            """{"tool":"other","roots":{"data":"${tempDir.resolve("data")}"}}""",
            """{"tool":"kast","roots":{}}""",
        ).forEach { contents ->
            Files.writeString(receipt, contents)

            val error = assertThrows(AnalysisException::class.java) {
                kastDataRoot(emptyMap<String, String>()::get, installRoot)
            }

            assertEquals("INSTALL_MANIFEST_INVALID", error.errorCode)
        }
    }

    @Test
    fun `public KAST_HOME owns every fallback path`() {
        val userHome = tempDir.resolve("user-home")
        val kastHome = tempDir.resolve("kast-home")
        val resolved = resolveKastPathDefaults(
            envLookup = mapOf(kastHomeEnv to kastHome.toString())::get,
            userHome = userHome,
        )

        assertEquals(kastHome.toAbsolutePath().normalize(), resolved.installRoot)
        assertEquals(resolved.installRoot.resolve("current/bin"), resolved.binDir)
        assertEquals(resolved.installRoot.resolve("current/lib"), resolved.libDir)
        assertEquals(resolved.installRoot.resolve("current/config"), resolved.configRoot)
        assertEquals(resolved.installRoot.resolve("state/data"), resolved.dataRoot)
        assertEquals(resolved.installRoot.resolve("state/cache"), resolved.cacheDir)
        assertEquals(resolved.installRoot.resolve("state/logs"), resolved.logsDir)
        assertEquals(resolved.installRoot.resolve("state/runtime"), resolved.runtimeDir)
        assertEquals(resolved.runtimeDir.resolve("daemons"), resolved.descriptorDir)
        assertEquals(resolved.runtimeDir, resolved.socketDir)
        assertEquals(resolved.binDir.resolve("kast"), resolved.cliBinary)
    }

    @Test
    fun `active receipt owns JVM default paths and config home`() {
        val userHome = tempDir.resolve("user-home")
        val kastHome = tempDir.resolve("kast-home")
        val receiptInstall = tempDir.resolve("receipt/install")
        val receiptBin = tempDir.resolve("receipt/bin")
        val receiptConfig = tempDir.resolve("receipt/config")
        val receiptData = tempDir.resolve("receipt/data")
        val receiptCache = tempDir.resolve("receipt/cache")
        val receiptLogs = tempDir.resolve("receipt/logs")
        val receiptRuntime = tempDir.resolve("receipt/runtime")
        val receiptCli = tempDir.resolve("receipt/bin/kast-cli")
        val receipt = kastHome.resolve("current/receipt.json")
        Files.createDirectories(receipt.parent)
        Files.writeString(
            receipt,
            """
            {
              "tool": "kast",
              "roots": {
                "install": "$receiptInstall",
                "bin": "$receiptBin",
                "config": "$receiptConfig",
                "data": "$receiptData",
                "cache": "$receiptCache",
                "runtime": "$receiptRuntime",
                "logs": "$receiptLogs",
                "locks": "$receiptInstall"
              },
              "entrypoints": {
                "shim": "$receiptCli",
                "activeBinary": "$receiptCli"
              }
            }
            """.trimIndent(),
        )
        val env = mapOf(kastHomeEnv to kastHome.toString())

        val resolved = resolveKastPathDefaults(env::get, userHome)

        assertEquals(receiptInstall.toAbsolutePath().normalize(), resolved.installRoot)
        assertEquals(receiptBin.toAbsolutePath().normalize(), resolved.binDir)
        assertEquals(receiptInstall.resolve("current/lib").toAbsolutePath().normalize(), resolved.libDir)
        assertEquals(receiptConfig.toAbsolutePath().normalize(), resolved.configRoot)
        assertEquals(receiptData.toAbsolutePath().normalize(), resolved.dataRoot)
        assertEquals(receiptCache.toAbsolutePath().normalize(), resolved.cacheDir)
        assertEquals(receiptLogs.toAbsolutePath().normalize(), resolved.logsDir)
        assertEquals(receiptRuntime.toAbsolutePath().normalize(), resolved.runtimeDir)
        assertEquals(receiptRuntime.resolve("daemons").toAbsolutePath().normalize(), resolved.descriptorDir)
        assertEquals(receiptRuntime.toAbsolutePath().normalize(), resolved.socketDir)
        assertEquals(receiptCli.toAbsolutePath().normalize(), resolved.cliBinary)
        assertEquals(receiptConfig.toAbsolutePath().normalize(), kastConfigHome(env::get, userHome))
    }

    @Test
    fun allPathsResolveFromConfigOnly() {
        val resolved = resolveKastPathDefaults()
        val defaults = KastConfig.defaults()

        assertEquals(resolved.descriptorDir, defaultDescriptorDirectory())
        assertEquals(resolved.installRoot.toString(), defaults.paths.installRoot.value)
        assertEquals(resolved.binDir.toString(), defaults.paths.binDir.value)
        assertEquals(resolved.libDir.toString(), defaults.paths.libDir.value)
        assertEquals(resolved.cacheDir.toString(), defaults.paths.cacheDir.value)
        assertEquals(resolved.logsDir.toString(), defaults.paths.logsDir.value)
        assertEquals(resolved.runtimeDir.toString(), defaults.paths.runtimeDir.value)
        assertEquals(resolved.descriptorDir.toString(), defaults.paths.descriptorDir.value)
        assertEquals(resolved.socketDir.toString(), defaults.paths.socketDir.value)
    }

    @Test
    fun `workspace data directory uses stable common directory identity for git remotes`() {
        val installRoot = tempDir.resolve("install-root")
        val workspaceRoot = tempDir.resolve("workspace")
        val commonDir = tempDir.resolve("main.git")
        val gitDir = commonDir.resolve("worktrees").resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            installRoot = { installRoot },
            gitWorkspaceResolver = {
                GitWorkspace(
                    toplevel = workspaceRoot,
                    commonDir = commonDir,
                    gitDir = gitDir,
                    remote = GitRemote(host = "github.com", owner = "amichne", repo = "kast"),
                )
            },
        )
        val worktreeHash = gitWorktreeHash(workspaceRoot, gitDir)

        assertEquals(
            installRoot.resolve(
                "state/data/workspaces/git/local/${gitCommonDirHash(commonDir)}/worktrees/workspace--$worktreeHash",
            ),
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
    fun `temporary local workspace data stays under the global data root`() {
        val dataRoot = tempDir.resolve("global-data")
        val workspaceRoot = tempDir.resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            dataRoot = { dataRoot },
            gitWorkspaceResolver = { null },
        )
        val expectedSegment = workspaceRoot
            .toAbsolutePath()
            .normalize()
            .toString()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .take(80)

        assertEquals(
            dataRoot.resolve("workspaces/local/$expectedSegment--${resolver.workspaceHash(workspaceRoot)}"),
            resolver.workspaceDataDirectory(workspaceRoot),
        )
        assertTrue(!Files.exists(dataRoot.resolve("workspaces/local-workspaces.json")))
        assertTrue(!Files.exists(workspaceRoot.resolve(".gradle/kast")))
    }

    @Test
    fun `local workspace data honors an existing registry mapping without rewriting it`() {
        val dataRoot = tempDir.resolve("global-data")
        val workspaceRoot = tempDir.resolve("workspace").toAbsolutePath().normalize()
        val registry = dataRoot.resolve("workspaces/local-workspaces.json")
        Files.createDirectories(registry.parent)
        val original = """{"$workspaceRoot":"existing-workspace-id"}"""
        Files.writeString(registry, original)
        val resolver = WorkspaceDirectoryResolver(
            dataRoot = { dataRoot },
            gitWorkspaceResolver = { null },
        )

        assertTrue(
            resolver.workspaceDataDirectory(workspaceRoot)
                .endsWith("$expectedLocalSegment--existing-workspace-id"),
        )
        assertEquals(original, Files.readString(registry))
    }

    @Test
    fun `local workspace registry ignores unrelated structured entries`() {
        val dataRoot = tempDir.resolve("global-data")
        val workspaceRoot = tempDir.resolve("workspace").toAbsolutePath().normalize()
        val registry = dataRoot.resolve("workspaces/local-workspaces.json")
        Files.createDirectories(registry.parent)
        Files.writeString(
            registry,
            """{"unrelated":{"owner":"user"},"$workspaceRoot":"existing-workspace-id"}""",
        )
        val resolver = WorkspaceDirectoryResolver(
            dataRoot = { dataRoot },
            gitWorkspaceResolver = { null },
        )

        assertTrue(
            resolver.workspaceDataDirectory(workspaceRoot)
                .endsWith("$expectedLocalSegment--existing-workspace-id"),
        )
    }

    @Test
    fun `remote changes do not change the stable repository directory`() {
        val dataRoot = tempDir.resolve("global-data")
        val workspaceRoot = tempDir.resolve("workspace")
        val commonDir = tempDir.resolve("common.git")
        val gitDir = commonDir.resolve("worktrees/workspace")
        var remote: GitRemote? = GitRemote(host = "github.com", owner = "amichne", repo = "kast")
        val resolver = WorkspaceDirectoryResolver(
            dataRoot = { dataRoot },
            gitWorkspaceResolver = {
                GitWorkspace(
                    toplevel = workspaceRoot,
                    commonDir = commonDir,
                    gitDir = gitDir,
                    remote = remote,
                )
            },
        )
        val expectedRepository = dataRoot
            .resolve("workspaces/git/local/${gitCommonDirHash(commonDir)}")
            .toAbsolutePath()
            .normalize()

        assertEquals(expectedRepository, resolver.repositoryDataDirectory(workspaceRoot))
        val initialWorkspace = resolver.workspaceDataDirectory(workspaceRoot)

        remote = GitRemote(host = "git.example.com", owner = "fork", repo = "renamed")
        assertEquals(expectedRepository, resolver.repositoryDataDirectory(workspaceRoot))
        assertEquals(initialWorkspace, resolver.workspaceDataDirectory(workspaceRoot))

        remote = null
        assertEquals(expectedRepository, resolver.repositoryDataDirectory(workspaceRoot))
        assertEquals(initialWorkspace, resolver.workspaceDataDirectory(workspaceRoot))
    }

    private val expectedLocalSegment: String
        get() = tempDir.resolve("workspace")
            .toAbsolutePath()
            .normalize()
            .toString()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .take(80)


    private companion object {
        val kastConfigHomeEnv: String = env("KAST", "CONFIG", "HOME")
        val kastDataHomeEnv: String = env("KAST", "DATA", "HOME")
        val kastHomeEnv: String = env("KAST", "HOME")

        fun env(vararg parts: String): String = parts.joinToString("_")
    }
}
