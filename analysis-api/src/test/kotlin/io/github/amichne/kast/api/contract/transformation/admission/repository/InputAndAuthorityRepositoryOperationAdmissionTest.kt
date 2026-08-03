package io.github.amichne.kast.api.contract.transformation.admission.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class InputAndAuthorityRepositoryOperationAdmissionTest : RepositoryOperationAdmissionFixture() {
    @Test
    fun `every applicable repository admission input is required`() {
        val valid = validInput()
        val cases = listOf(
            valid.copy(repository = null) to RepositoryOperationRejection.ApplicableInputMissing(
                ApplicableInputKind.REPOSITORY,
            ),
            valid.copy(sourceState = null) to RepositoryOperationRejection.ApplicableInputMissing(
                ApplicableInputKind.SOURCE_STATE,
            ),
            valid.copy(buildOwnership = null) to RepositoryOperationRejection.ApplicableInputMissing(
                ApplicableInputKind.BUILD_OWNERSHIP,
            ),
            valid.copy(scope = null) to RepositoryOperationRejection.ApplicableInputMissing(
                ApplicableInputKind.SCOPE,
            ),
            valid.copy(resourceBounds = null) to RepositoryOperationRejection.ResourceBoundMissing(
                ResourceBoundKind.TIME,
            ),
        )

        cases.forEach { (input, expected) -> assertRejected(expected, input) }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `malformed and moving JVM collections produce typed rejection`() {
        val nullScope = listOf(null) as List<RawScopeSelector>
        assertRejected(
            RepositoryOperationRejection.ApplicableInputMissing(ApplicableInputKind.SCOPE),
            validInput(scope = nullScope),
        )

        val nullCompilationUnits = listOf(null) as List<RawCompilationUnitInput>
        assertRejected(
            RepositoryOperationRejection.SemanticConfigurationIncomplete(
                SemanticConfigurationField.COMPILATION_UNITS,
                null,
            ),
            validInput(
                buildOwnership = RawBuildOwnershipEvidence.Available(nullCompilationUnits),
            ),
        )

        val nullSourceInputs = listOf(null) as List<RawSourceInput>
        assertRejected(
            RepositoryOperationRejection.SourceStateEvidenceMissing(
                SourceStateEvidenceKind.INVENTORY,
                null,
            ),
            validInput(sourceState = validSourceState(inputs = nullSourceInputs)),
        )

        val wrongSourceInputs = listOf("not-a-source-input") as List<RawSourceInput>
        assertRejected(
            RepositoryOperationRejection.SourceStateEvidenceMissing(
                SourceStateEvidenceKind.INVENTORY,
                null,
            ),
            validInput(sourceState = validSourceState(inputs = wrongSourceInputs)),
        )

        val nullableSettings = linkedMapOf<String?, String?>("progressive" to "true", null to "invalid")
            as Map<String, String>
        val unit = validCompilationUnit().let { validUnit ->
            validUnit.copy(
                compiler = requireNotNull(validUnit.compiler).copy(languageSettings = nullableSettings),
            )
        }
        assertRejected(
            RepositoryOperationRejection.SemanticConfigurationIncomplete(
                SemanticConfigurationField.LANGUAGE_SETTINGS,
                "unit-main",
            ),
            validInput(buildOwnership = available(unit)),
        )

        val backingScope = mutableListOf<RawScopeSelector>(
            RawScopeSelector.Module("application"),
            RawScopeSelector.Module("application"),
        )
        val movingScope = object : AbstractList<RawScopeSelector>() {
            override val size: Int
                get() = backingScope.size

            override fun get(index: Int): RawScopeSelector = backingScope[index].also {
                if (index == 0 && backingScope.size > 1) backingScope.removeLast()
            }
        }
        assertRejected(
            RepositoryOperationRejection.ApplicableInputMissing(ApplicableInputKind.SCOPE),
            validInput(scope = movingScope),
        )
    }

    @Test
    fun `source admission reports time and memory exhaustion by bound`() {
        val memoryRoot = repositoryRoot("memory-bound")
        val relativePath = "misleading-layout/src/Application.kt"
        val sourcePath = memoryRoot.resolve(relativePath)
        Files.writeString(sourcePath, "package sample\nclass MemoryBound\n")
        assertRejected(
            RepositoryOperationRejection.ResourceBoundExceeded(ResourceBoundKind.MEMORY),
            validInput(
                memoryRoot,
                sourceState = validSourceState(
                    inputs = listOf(
                        includedSource(
                            relativePath,
                            RawSourceInputKind.TRACKED_CHANGE,
                            sha256(sourcePath),
                        ),
                    ),
                    root = memoryRoot,
                ),
                resourceBounds = validBounds().copy(memoryLimitBytes = 1),
            ),
        )

        val inventoryRoot = repositoryRoot("inventory-time-bound")
        assertRejectedResult(
            RepositoryOperationRejection.ResourceBoundExceeded(ResourceBoundKind.TIME),
            RepositoryOperationAdmissionParser(
                rawInput = validInput(
                    inventoryRoot,
                    resourceBounds = validBounds().copy(timeLimitMillis = 500),
                ),
                stabilityCheckpoint = SourceStateStabilityCheckpoint { Thread.sleep(600) },
            ).parse(),
        )

        val contentRoot = repositoryRoot("content-time-bound")
        val contentPath = contentRoot.resolve(relativePath)
        Files.writeString(contentPath, "package sample\nclass ContentBound\n")
        assertRejectedResult(
            RepositoryOperationRejection.ResourceBoundExceeded(ResourceBoundKind.TIME),
            RepositoryOperationAdmissionParser(
                rawInput = validInput(
                    contentRoot,
                    sourceState = validSourceState(
                        inputs = listOf(
                            includedSource(
                                relativePath,
                                RawSourceInputKind.TRACKED_CHANGE,
                                sha256(contentPath),
                            ),
                        ),
                        root = contentRoot,
                    ),
                    resourceBounds = validBounds().copy(timeLimitMillis = 500),
                ),
                contentReadCheckpoint = SourceContentReadCheckpoint { Thread.sleep(600) },
            ).parse(),
        )
    }

    @Test
    fun `missing and invalid resource bounds are rejected by kind`() {
        val valid = validBounds()
        val cases = listOf(
            Triple(
                ResourceBoundKind.TIME,
                valid.copy(timeLimitMillis = null),
                valid.copy(timeLimitMillis = -1),
            ),
            Triple(
                ResourceBoundKind.MEMORY,
                valid.copy(memoryLimitBytes = null),
                valid.copy(memoryLimitBytes = -1),
            ),
            Triple(
                ResourceBoundKind.DEPTH,
                valid.copy(traversalDepthLimit = null),
                valid.copy(traversalDepthLimit = -1),
            ),
            Triple(
                ResourceBoundKind.PATHS,
                valid.copy(pathLimit = null),
                valid.copy(pathLimit = -1),
            ),
            Triple(
                ResourceBoundKind.RESULTS,
                valid.copy(resultLimit = null),
                valid.copy(resultLimit = -1),
            ),
        )

        cases.forEach { (kind, missing, invalid) ->
            assertRejected(
                RepositoryOperationRejection.ResourceBoundMissing(kind),
                validInput(resourceBounds = missing),
            )
            assertRejected(
                RepositoryOperationRejection.ResourceBoundInvalid(kind, -1),
                validInput(resourceBounds = invalid),
            )
        }

        val inaccessibleScope = object : AbstractList<RawScopeSelector>() {
            override val size: Int
                get() = error("scope must not be inspected before bounds")

            override fun get(index: Int): RawScopeSelector = error("scope must not be inspected before bounds")
        }
        assertRejected(
            RepositoryOperationRejection.ResourceBoundMissing(ResourceBoundKind.TIME),
            validInput(scope = inaccessibleScope).copy(resourceBounds = null),
        )
    }

    @Test
    fun `declared admission time is not shortened to an internal Git timeout`() {
        val root = repositoryRoot("declared-time-bound")
        var nowNanos = 0L

        val result = RepositoryOperationAdmissionParser(
            rawInput = validInput(
                root,
                resourceBounds = validBounds().copy(timeLimitMillis = 6_000),
            ),
            stabilityCheckpoint = SourceStateStabilityCheckpoint {
                nowNanos = 5_500L * 1_000_000L
            },
            nanoTime = { nowNanos },
        ).parse()

        assertInstanceOf(RepositoryOperationAdmission.Result.Admitted::class.java, result)
    }

    @Test
    fun `relative dot and symlink aliases converge on one canonical worktree root`() {
        val root = repositoryRoot("repository")
        val alias = temporaryDirectory.resolve("repository-alias")
        Files.createSymbolicLink(alias, root)
        val absolute = admitted(validInput(root))
        val relative = admitted(
            validInput(root).copy(
                repository = RawRepositoryInput(
                    requestedRoot = "repository/.",
                    baseDirectory = temporaryDirectory.toString(),
                ),
            ),
        )
        val symlink = admitted(
            validInput(root).copy(
                repository = RawRepositoryInput(
                    requestedRoot = alias.toString(),
                    baseDirectory = temporaryDirectory.toString(),
                ),
            ),
        )

        assertEquals(root.toRealPath().toString(), absolute.repositoryState.canonicalRoot.value)
        assertEquals(absolute.repositoryState.canonicalRoot, relative.repositoryState.canonicalRoot)
        assertEquals(absolute.repositoryState.canonicalRoot, symlink.repositoryState.canonicalRoot)
        assertEquals(absolute.repositoryState.identity, relative.repositoryState.identity)
        assertEquals(absolute.repositoryState.identity, symlink.repositoryState.identity)
    }

    @Test
    fun `separate worktrees retain distinct authority at the same revision`() {
        val repository = repositoryRoot("main-worktree")
        val firstRoot = temporaryDirectory.resolve("first-worktree")
        val secondRoot = temporaryDirectory.resolve("second-worktree")
        git(repository, "worktree", "add", "--quiet", "--detach", firstRoot.toString(), "HEAD")
        git(repository, "worktree", "add", "--quiet", "--detach", secondRoot.toString(), "HEAD")
        val first = admitted(validInput(firstRoot))
        val second = admitted(validInput(secondRoot))

        assertEquals(first.repositoryState.sourceState.revision, second.repositoryState.sourceState.revision)
        assertNotEquals(first.repositoryState.canonicalRoot, second.repositoryState.canonicalRoot)
        assertNotEquals(first.repositoryState.identity, second.repositoryState.identity)
    }

    @Test
    fun `linked worktree authority reads do not follow a replacement symlink`() {
        val repository = repositoryRoot("authority-race-main")
        val linkedRoot = temporaryDirectory.resolve("authority-race-linked")
        git(repository, "worktree", "add", "--quiet", "--detach", linkedRoot.toString(), "HEAD")
        val dotGit = linkedRoot.resolve(".git")
        val replacement = temporaryDirectory.resolve("outside-authority-reference")
        Files.writeString(replacement, Files.readString(dotGit))
        val input = validInput(linkedRoot)

        assertRejectedResult(
            RepositoryOperationRejection.RepositoryRootUnresolvable(linkedRoot.toString()),
            RepositoryOperationAdmissionParser(
                rawInput = input,
                authorityReadCheckpoint = GitAuthorityReadCheckpoint { path ->
                    if (path.fileName.toString() == ".git") {
                        Files.delete(path)
                        Files.createSymbolicLink(path, replacement)
                    }
                },
            ).parse(),
        )
    }

    @Test
    fun `an unregistered worktree cannot borrow another repository git directory`() {
        val authority = repositoryRoot("git-authority")
        val impostor = temporaryDirectory.resolve("git-impostor")
        Files.createDirectories(impostor.resolve("misleading-layout/src"))
        Files.writeString(
            impostor.resolve("misleading-layout/src/Application.kt"),
            "package sample\nclass Application\n",
        )
        Files.createSymbolicLink(impostor.resolve(".git"), authority.resolve(".git"))

        assertRejected(
            RepositoryOperationRejection.RepositoryRootUnresolvable(impostor.toString()),
            validInput(impostor),
        )
    }
}
