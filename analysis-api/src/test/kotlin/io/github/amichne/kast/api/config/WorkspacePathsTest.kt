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

    @Test
    fun `shared local fixtures map to exact deterministic global paths`() {
        val dataRoot = tempDir.resolve("global-data")
        val resolver = WorkspaceDirectoryResolver(
            dataRoot = { dataRoot },
            gitWorkspaceResolver = { null },
        )

        sharedLocalFixtures().forEach { (workspaceRoot, relativePath) ->
            assertEquals(
                dataRoot.resolve("workspaces").resolve(relativePath),
                resolver.workspaceDataDirectory(Path.of(workspaceRoot)),
            )
        }
        assertTrue(!Files.exists(dataRoot.resolve("workspaces/local-workspaces.json")))
    }

    @Test
    fun `shared git fixtures map to exact deterministic global paths`() {
        val dataRoot = tempDir.resolve("global-data")

        sharedGitFixtures().forEach { fixture ->
            val resolver = WorkspaceDirectoryResolver(
                dataRoot = { dataRoot },
                gitWorkspaceResolver = {
                    GitWorkspace(
                        toplevel = fixture.toplevel,
                        commonDir = fixture.commonDir,
                        gitDir = fixture.gitDir,
                        remote = GitRemote("ignored.example.com", "ignored", "ignored"),
                    )
                },
            )

            assertEquals(
                dataRoot.resolve("workspaces").resolve(fixture.relativePath),
                resolver.workspaceDataDirectory(fixture.toplevel),
            )
        }
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
        val repository = installRoot.resolve(
            "state/data/workspaces/git/local/${gitCommonDirHash(commonDir)}",
        )

        assertEquals(repository, resolver.repositoryDataDirectory(firstRoot))
        assertEquals(repository, resolver.repositoryDataDirectory(secondRoot))
        assertTrue(first.startsWith(repository.resolve("worktrees")))
        assertTrue(second.startsWith(repository.resolve("worktrees")))
        assertTrue(first != second, "sibling worktrees should not share workspace data: first=$first second=$second")
        assertEquals(first, resolver.workspaceCacheDirectory(firstRoot).parent)
        assertEquals(second.resolve("cache/source-index.db"), resolver.workspaceDatabasePath(secondRoot))
    }

    @Test
    fun `non git workspace has no repository snapshot directory`() {
        val workspaceRoot = tempDir.resolve("workspace")
        val resolver = WorkspaceDirectoryResolver(
            dataRoot = { tempDir.resolve("data") },
            gitWorkspaceResolver = { null },
        )

        assertNull(resolver.repositoryDataDirectory(workspaceRoot))
        assertNull(resolver.workspaceIdentity(workspaceRoot).repositoryDataDirectory)
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
            installRoot.resolve("state/data/workspaces/git/local/${gitCommonDirHash(commonDir)}/worktrees/workspace--${gitWorktreeHash(workspaceRoot, gitDir)}"),
            resolver.workspaceDataDirectory(workspaceRoot),
        )
    }

    @Test
    fun `unique legacy remote worktree state migrates into stable repository before resolution`() {
        val dataRoot = tempDir.resolve("global-data")
        val workspaceRoot = tempDir.resolve("workspace")
        val commonDir = tempDir.resolve("main.git")
        val gitDir = commonDir.resolve("worktrees/workspace")
        val leaf = "workspace--${gitWorktreeHash(workspaceRoot, gitDir)}"
        val legacyRepository = dataRoot.resolve("workspaces/git/git.example.com/org/platform/kast")
        val legacyWorkspace = legacyRepository.resolve("worktrees/$leaf")
        val siblingLegacyWorkspace = legacyRepository.resolve("worktrees/sibling--unchanged")
        Files.createDirectories(legacyWorkspace)
        Files.createDirectories(siblingLegacyWorkspace)
        Files.createDirectories(legacyRepository.resolve("snapshots/retained"))
        Files.createDirectories(legacyRepository.resolve("snapshots/retained/worktrees/$leaf"))
        Files.writeString(legacyWorkspace.resolve("config.toml"), "[indexing]\n")
        val resolver = WorkspaceDirectoryResolver(
            dataRoot = { dataRoot },
            gitWorkspaceResolver = {
                GitWorkspace(
                    toplevel = workspaceRoot,
                    commonDir = commonDir,
                    gitDir = gitDir,
                    remote = GitRemote(host = "git.example.com", owner = "changed", repo = "origin"),
                )
            },
        )
        val expected = dataRoot.resolve(
            "workspaces/git/local/${gitCommonDirHash(commonDir)}/worktrees/$leaf",
        ).toAbsolutePath().normalize()

        assertEquals(expected, resolver.workspaceDataDirectory(workspaceRoot))
        assertTrue(Files.isRegularFile(expected.resolve("config.toml")))
        assertTrue(Files.notExists(legacyWorkspace))
        assertTrue(Files.isDirectory(siblingLegacyWorkspace))
        assertTrue(Files.isDirectory(legacyRepository.resolve("snapshots/retained")))
        assertEquals(expected, resolver.workspaceDataDirectory(workspaceRoot))
    }

    @Test
    fun `stable and legacy worktree state conflict fails closed`() {
        val fixture = gitMigrationFixture()
        Files.createDirectories(fixture.stableWorkspace)
        Files.createDirectories(fixture.legacyWorkspaces.single())

        val failure = assertThrows(AnalysisException::class.java) {
            fixture.resolver.workspaceDataDirectory(fixture.workspaceRoot)
        }

        assertEquals("WORKSPACE_STATE_MIGRATION_CONFLICT", failure.errorCode)
        assertTrue(Files.isDirectory(fixture.stableWorkspace))
        assertTrue(Files.isDirectory(fixture.legacyWorkspaces.single()))
    }

    @Test
    fun `stable worktree state is reused when no legacy state exists`() {
        val fixture = gitMigrationFixture()
        Files.createDirectories(fixture.stableWorkspace)
        Files.writeString(fixture.stableWorkspace.resolve("config.toml"), "[cache]\n")

        assertEquals(
            fixture.stableWorkspace,
            fixture.resolver.workspaceDataDirectory(fixture.workspaceRoot),
        )
        assertTrue(Files.isRegularFile(fixture.stableWorkspace.resolve("config.toml")))
    }

    @Test
    fun `multiple legacy worktree states fail closed`() {
        val fixture = gitMigrationFixture(legacyRepositoryCount = 2)
        fixture.legacyWorkspaces.forEach(Files::createDirectories)

        val failure = assertThrows(AnalysisException::class.java) {
            fixture.resolver.workspaceDataDirectory(fixture.workspaceRoot)
        }

        assertEquals("WORKSPACE_STATE_MIGRATION_AMBIGUOUS", failure.errorCode)
        fixture.legacyWorkspaces.forEach { assertTrue(Files.isDirectory(it)) }
        assertTrue(Files.notExists(fixture.stableWorkspace))
    }

    @Test
    fun `legacy repository traversal depth fails closed`() {
        val fixture = gitMigrationFixture()
        val legacyRoot = fixture.legacyWorkspaces.single()
            .let { workspace -> workspace.parent.parent.parent.parent }
        val deepRepository = (1..33).fold(legacyRoot) { path, depth ->
            path.resolve("group-$depth")
        }
        Files.createDirectories(
            deepRepository.resolve("worktrees/${fixture.stableWorkspace.fileName}"),
        )

        val failure = assertThrows(AnalysisException::class.java) {
            fixture.resolver.workspaceDataDirectory(fixture.workspaceRoot)
        }

        assertEquals("WORKSPACE_STATE_MIGRATION_DEPTH_EXCEEDED", failure.errorCode)
        assertTrue(Files.notExists(fixture.stableWorkspace))
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
        fun `falls back to config root under public KAST_HOME`() {
            val env = emptyMap<String, String>()
            val userHome = tempDir.resolve("user-home")
            val result = kastConfigHome(env::get, userHome)
            assertEquals(
                userHome.resolve(".local/share/kast/current/config").toAbsolutePath().normalize(),
                result,
            )
        }
    }

    @Nested
    inner class DefaultDescriptorDirectoryTest {
        @Test
        fun `resolves to descriptor directory from config defaults`() {
            val result = defaultDescriptorDirectory()
            assertEquals(resolveKastPathDefaults().descriptorDir, result)
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
        fun `workspace socket path is only the normalized root hash under its socket directory`() {
            val workspaceRoot = tempDir.resolve("workspace/../workspace")
            val socketDirectory = Path.of("/runtime")
            val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
            val expectedHash = io.github.amichne.kast.api.validation.FileHashing.sha256(
                normalizedRoot.toString(),
            ).take(12)

            assertEquals(
                socketDirectory.resolve("kast-$expectedHash.sock").toAbsolutePath().normalize(),
                socketPathForWorkspaceRoot(workspaceRoot, socketDirectory),
            )
        }

        @Test
        fun localWorkspaceDatabasePathUsesIsolatedJunitConfigHomeByDefault() {
            val workspaceRoot = tempDir.resolve("workspace")

            val databasePath = workspaceDatabasePath(workspaceRoot)

            assertTrue(
                databasePath.startsWith(kastDataRoot().resolve("workspaces")),
                "databasePath=$databasePath dataRoot=${kastDataRoot()}",
            )
            assertTrue(
                !databasePath.startsWith(workspaceRoot.toAbsolutePath().normalize()),
                "databasePath=$databasePath workspaceRoot=$workspaceRoot",
            )
        }
    }

    @Nested
    inner class WorkspaceIdentityTest {
        @Test
        fun `workspace identity resolves Git layout once`() {
            val workspaceRoot = Files.createDirectories(tempDir.resolve("workspace"))
            val commonDir = tempDir.resolve("common.git")
            var gitDiscoveryCount = 0
            val resolver = WorkspaceDirectoryResolver(
                dataRoot = { tempDir.resolve("data") },
                gitWorkspaceResolver = {
                    gitDiscoveryCount += 1
                    GitWorkspace(
                        toplevel = workspaceRoot,
                        commonDir = commonDir,
                        gitDir = commonDir.resolve("worktrees/workspace"),
                        remote = GitRemote("github.com", "amichne", "kast"),
                    )
                },
            )

            resolver.workspaceIdentity(workspaceRoot)

            assertEquals(1, gitDiscoveryCount)
        }

        @Test
        fun `workspace identity keeps index and socket paths isolated by workspace root`() {
            val resolver = WorkspaceDirectoryResolver(
                installRoot = { tempDir.resolve("install-root") },
                gitWorkspaceResolver = { null },
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

    private val expectedLocalSegment: String
        get() = tempDir.resolve("workspace")
            .toAbsolutePath()
            .normalize()
            .toString()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .take(80)

    private fun sharedLocalFixtures(): List<Pair<String, String>> =
        requireNotNull(javaClass.getResourceAsStream("/workspace-local-layout-fixtures.tsv"))
            .bufferedReader()
            .useLines { lines ->
                lines
                    .filterNot { line -> line.startsWith("#") || line.isBlank() }
                    .map { line ->
                        val fields = line.split('\t')
                        require(fields.size == 2) { "Invalid local workspace layout fixture: $line" }
                        fields[0] to fields[1]
                    }
                    .toList()
            }

    private fun sharedGitFixtures(): List<GitLayoutFixture> =
        requireNotNull(javaClass.getResourceAsStream("/workspace-git-layout-fixtures.tsv"))
            .bufferedReader()
            .useLines { lines ->
                lines
                    .filterNot { line -> line.startsWith("#") || line.isBlank() }
                    .map { line ->
                        val fields = line.split('\t')
                        require(fields.size == 4) { "Invalid Git workspace layout fixture: $line" }
                        GitLayoutFixture(
                            toplevel = Path.of(fields[0]),
                            commonDir = Path.of(fields[1]),
                            gitDir = Path.of(fields[2]),
                            relativePath = fields[3],
                        )
                    }
                    .toList()
            }

    private data class GitLayoutFixture(
        val toplevel: Path,
        val commonDir: Path,
        val gitDir: Path,
        val relativePath: String,
    )

    private fun gitMigrationFixture(legacyRepositoryCount: Int = 1): GitMigrationFixture {
        val dataRoot = tempDir.resolve("migration-${System.nanoTime()}")
        val workspaceRoot = tempDir.resolve("workspace-${System.nanoTime()}")
        val commonDir = tempDir.resolve("common-${System.nanoTime()}.git")
        val gitDir = commonDir.resolve("worktrees/${workspaceRoot.fileName}")
        val leaf = "${workspaceRoot.fileName}--${gitWorktreeHash(workspaceRoot, gitDir)}"
        val resolver = WorkspaceDirectoryResolver(
            dataRoot = { dataRoot },
            gitWorkspaceResolver = {
                GitWorkspace(
                    toplevel = workspaceRoot,
                    commonDir = commonDir,
                    gitDir = gitDir,
                    remote = null,
                )
            },
        )
        return GitMigrationFixture(
            resolver = resolver,
            workspaceRoot = workspaceRoot,
            stableWorkspace = dataRoot.resolve(
                "workspaces/git/local/${gitCommonDirHash(commonDir)}/worktrees/$leaf",
            ).toAbsolutePath().normalize(),
            legacyWorkspaces = (1..legacyRepositoryCount).map { index ->
                dataRoot.resolve("workspaces/git/host-$index/owner/group/repo/worktrees/$leaf")
                    .toAbsolutePath()
                    .normalize()
            },
        )
    }

    private data class GitMigrationFixture(
        val resolver: WorkspaceDirectoryResolver,
        val workspaceRoot: Path,
        val stableWorkspace: Path,
        val legacyWorkspaces: List<Path>,
    )

    private companion object {
        val kastConfigHomeEnv: String = env("KAST", "CONFIG", "HOME")
        val kastDataHomeEnv: String = env("KAST", "DATA", "HOME")
        val kastHomeEnv: String = env("KAST", "HOME")

        fun env(vararg parts: String): String = parts.joinToString("_")
    }
}
