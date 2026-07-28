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

class WorkspacePathLayoutTest {
    @TempDir
    lateinit var tempDir: Path

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
}
