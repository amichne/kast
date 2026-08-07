package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.validation.FileHashing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

class WorkspacePathLayoutTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `canonical workspace path maps directly to one full digest directory`() {
        val dataRoot = tempDir.resolve("global-data")
        val workspaceRoot = tempDir.resolve("workspace")
        val commonDir = tempDir.resolve("main.git")
        val resolver = WorkspaceDirectoryResolver(
            dataRoot = { dataRoot },
            gitWorkspaceResolver = {
                GitWorkspace(
                    toplevel = workspaceRoot,
                    commonDir = commonDir,
                    gitDir = commonDir.resolve("worktrees/workspace"),
                    remote = null,
                )
            },
        )
        val canonicalRoot = io.github.amichne.kast.api.contract.NormalizedPath.of(workspaceRoot).toJavaPath()
        val expectedKey = FileHashing.sha256(canonicalRoot.toString())

        assertEquals(64, expectedKey.length)
        assertEquals(
            dataRoot.resolve("workspaces").resolve(expectedKey).toAbsolutePath().normalize(),
            resolver.workspaceDataDirectory(workspaceRoot),
        )
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
                when (root) {
                    io.github.amichne.kast.api.contract.NormalizedPath.of(firstRoot).toJavaPath() -> GitWorkspace(
                        toplevel = firstRoot,
                        commonDir = commonDir,
                        gitDir = commonDir.resolve("worktrees/kast"),
                        remote = remote,
                    )
                    io.github.amichne.kast.api.contract.NormalizedPath.of(secondRoot).toJavaPath() -> GitWorkspace(
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
            "state/data/repositories/${RepositoryPathKey.fromCommonDirectory(io.github.amichne.kast.api.contract.NormalizedPath.of(commonDir)).value}",
        )

        val repositoryAuthority = WorkspaceRepository.Git(
            io.github.amichne.kast.api.contract.NormalizedPath.ofAbsolute(repository),
        )
        assertEquals(repositoryAuthority, resolver.repository(firstRoot))
        assertEquals(repositoryAuthority, resolver.repository(secondRoot))
        assertTrue(first.startsWith(installRoot.resolve("state/data/workspaces")))
        assertTrue(second.startsWith(installRoot.resolve("state/data/workspaces")))
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

        assertEquals(WorkspaceRepository.None, resolver.repository(workspaceRoot))
        assertEquals(WorkspaceRepository.None, resolver.workspaceIdentity(workspaceRoot).repository)
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
            installRoot.resolve(
                "state/data/workspaces/${WorkspacePathKey.fromCanonicalPath(io.github.amichne.kast.api.contract.NormalizedPath.of(workspaceRoot)).value}",
            ),
            resolver.workspaceDataDirectory(workspaceRoot),
        )
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

}
