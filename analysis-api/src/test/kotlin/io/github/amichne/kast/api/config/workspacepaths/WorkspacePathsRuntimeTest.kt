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

class WorkspacePathsRuntimeTest {
    @TempDir
    lateinit var tempDir: Path

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
        fun `workspace socket path is indexer qualified and uses the canonical root hash`() {
            val realWorkspaceRoot = Files.createDirectories(tempDir.resolve("real-workspace"))
            val workspaceRoot = tempDir.resolve("workspace-link")
            Files.createSymbolicLink(workspaceRoot, realWorkspaceRoot)
            val socketDirectory = Path.of("/runtime")
            val expectedHash = io.github.amichne.kast.api.validation.FileHashing.sha256(
                realWorkspaceRoot.toRealPath().toString(),
            ).take(12)

            assertEquals(
                socketDirectory.resolve("kast-indexer-$expectedHash.sock").toAbsolutePath().normalize(),
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
        fun `admitted workspace identity uses exact launch paths without Git rediscovery`() {
            val workspaceRoot = Files.createDirectories(tempDir.resolve("admitted-workspace"))
            val workspaceData = tempDir.resolve("admitted-data/worktree")
            val repositoryData = tempDir.resolve("admitted-data/repository")
            val descriptors = tempDir.resolve("runtime/daemons")

            val identity = WorkspaceIdentity.fromAdmittedWorkspaceLayout(
                workspaceRoot = workspaceRoot,
                workspaceDataDirectory = workspaceData,
                repositoryDataDirectory = repositoryData,
                descriptorDirectory = descriptors,
            )

            assertEquals(workspaceData.toAbsolutePath(), identity.workspaceDataDirectoryPath)
            assertEquals(repositoryData.toAbsolutePath(), identity.repositoryDataDirectoryPath)
            assertEquals(workspaceData.resolve("cache/source-index.db").toAbsolutePath(), identity.sourceIndexDatabaseFile)
            assertEquals(descriptors.toAbsolutePath(), identity.descriptorDirectoryFile)
        }

        @Test
        fun `workspace aliases resolve to one canonical runtime identity`() {
            val realWorkspaceRoot = Files.createDirectories(tempDir.resolve("real-workspace"))
            val aliasWorkspaceRoot = tempDir.resolve("workspace-link")
            Files.createSymbolicLink(aliasWorkspaceRoot, realWorkspaceRoot)
            val resolver = WorkspaceDirectoryResolver(
                dataRoot = { tempDir.resolve("data") },
                gitWorkspaceResolver = { null },
            )

            val direct = resolver.workspaceIdentity(realWorkspaceRoot)
            val alias = resolver.workspaceIdentity(aliasWorkspaceRoot)

            assertEquals(direct.canonicalWorkspaceRoot, alias.canonicalWorkspaceRoot)
            assertEquals(direct.workspaceId, alias.workspaceId)
            assertEquals(direct.sourceIndexDatabasePath, alias.sourceIndexDatabasePath)
            assertEquals(direct.defaultSocketPath, alias.defaultSocketPath)
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

    private companion object {
        val kastConfigHomeEnv: String = env("KAST", "CONFIG", "HOME")
        val kastDataHomeEnv: String = env("KAST", "DATA", "HOME")
        val kastHomeEnv: String = env("KAST", "HOME")

        fun env(vararg parts: String): String = parts.joinToString("_")
    }
}
