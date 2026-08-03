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

class SourceInventoryRepositoryOperationAdmissionTest : RepositoryOperationAdmissionFixture() {
    @Test
    fun `dirty source evidence must match the live Git worktree and file content`() {
        val root = repositoryRoot()
        val trackedRelativePath = "misleading-layout/src/Application.kt"
        val trackedPath = root.resolve(trackedRelativePath)
        Files.writeString(trackedPath, "package sample\nclass Application(val changed: Boolean)\n")

        assertRejected(
            RepositoryOperationRejection.SourceStateConflict(trackedRelativePath),
            validInput(root, sourceState = validSourceState(root = root)),
        )
        val tracked = includedSource(
            trackedRelativePath,
            RawSourceInputKind.TRACKED_CHANGE,
            sha256(trackedPath),
        )
        assertRejected(
            RepositoryOperationRejection.SourceStateConflict(trackedRelativePath),
            validInput(
                root,
                sourceState = validSourceState(
                    inputs = listOf(tracked.copy(contentSha256 = DIGEST_A)),
                    root = root,
                ),
            ),
        )

        val untrackedRelativePath = "src/New.kt"
        val untrackedPath = root.resolve(untrackedRelativePath)
        Files.createDirectories(untrackedPath.parent)
        Files.writeString(untrackedPath, "class New")
        assertRejected(
            RepositoryOperationRejection.SourceStateConflict(untrackedRelativePath),
            validInput(
                root,
                sourceState = validSourceState(inputs = listOf(tracked), root = root),
            ),
        )

        val operation = admitted(
            validInput(
                root,
                sourceState = validSourceState(
                    inputs = listOf(
                        tracked,
                        includedSource(
                            untrackedRelativePath,
                            RawSourceInputKind.UNTRACKED,
                            sha256(untrackedPath),
                        ),
                    ),
                    root = root,
                ),
            ),
        )
        assertEquals(2, operation.repositoryState.sourceState.inputs.size)
    }

    @Test
    fun `tracked deletion is represented distinctly from a present source file`() {
        val root = repositoryRoot()
        val relativePath = "misleading-layout/src/Application.kt"
        Files.delete(root.resolve(relativePath))
        val deleted = RawSourceInput(
            path = relativePath,
            kind = RawSourceInputKind.TRACKED_CHANGE,
            presence = RawSourceInputPresence.DELETED,
            disposition = RawSourceInputDisposition.INCLUDED,
            contentSha256 = null,
        )

        val operation = admitted(
            validInput(
                root,
                sourceState = validSourceState(inputs = listOf(deleted), root = root),
            ),
        )
        assertInstanceOf(
            ExactSourceInput.DeletedTrackedInput::class.java,
            operation.repositoryState.sourceState.inputs.single(),
        )
        assertRejected(
            RepositoryOperationRejection.SourceStateConflict(relativePath),
            validInput(
                root,
                sourceState = validSourceState(
                    inputs = listOf(
                        includedSource(relativePath, RawSourceInputKind.TRACKED_CHANGE, DIGEST_A),
                    ),
                    root = root,
                ),
            ),
        )
    }

    @Test
    fun `index flags cannot hide a changed tracked source`() {
        listOf("--assume-unchanged", "--skip-worktree").forEachIndexed { index, flag ->
            val root = repositoryRoot("hidden-dirty-$index")
            val relativePath = "misleading-layout/src/Application.kt"
            git(root, "update-index", flag, relativePath)
            Files.writeString(root.resolve(relativePath), "package sample\nclass HiddenChange$index\n")

            assertRejected(
                RepositoryOperationRejection.SourceStateConflict(relativePath),
                validInput(root, sourceState = validSourceState(root = root)),
            )
        }
    }

    @Test
    fun `build-owned ignored generated sources cannot be omitted`() {
        val root = repositoryRoot()
        val relativePath = "misleading-layout/src/generated/Generated.kt"
        val generatedPath = root.resolve(relativePath)
        Files.createDirectories(generatedPath.parent)
        Files.writeString(
            root.resolve(".git/info/exclude"),
            "\n$relativePath\n",
            java.nio.file.StandardOpenOption.APPEND,
        )
        Files.writeString(generatedPath, "package sample\nclass Generated\n")

        assertRejected(
            RepositoryOperationRejection.SourceStateConflict(relativePath),
            validInput(root, sourceState = validSourceState(root = root)),
        )
        val operation = admitted(
            validInput(
                root,
                sourceState = validSourceState(
                    inputs = listOf(
                        includedSource(relativePath, RawSourceInputKind.GENERATED, sha256(generatedPath)),
                    ),
                    root = root,
                ),
            ),
        )
        assertEquals(
            RawSourceInputKind.GENERATED,
            operation.repositoryState.sourceState.inputs.single().kind,
        )
    }

    @Test
    fun `a clean tracked source cannot be admitted as generated`() {
        val root = repositoryRoot()
        val relativePath = "misleading-layout/src/Application.kt"
        val sourcePath = root.resolve(relativePath)

        assertRejected(
            RepositoryOperationRejection.SourceStateConflict(relativePath),
            validInput(
                root,
                sourceState = validSourceState(
                    inputs = listOf(
                        includedSource(
                            relativePath,
                            RawSourceInputKind.GENERATED,
                            sha256(sourcePath),
                        ),
                    ),
                    root = root,
                ),
            ),
        )
    }

    @Test
    fun `an authored untracked source cannot be admitted as generated`() {
        val root = repositoryRoot()
        val relativePath = "misleading-layout/src/Authored.kt"
        val sourcePath = root.resolve(relativePath)
        Files.writeString(sourcePath, "class Authored")

        assertRejected(
            RepositoryOperationRejection.SourceStateConflict(relativePath),
            validInput(
                root,
                sourceState = validSourceState(
                    inputs = listOf(
                        includedSource(relativePath, RawSourceInputKind.GENERATED, sha256(sourcePath)),
                    ),
                    root = root,
                ),
            ),
        )
    }

    @Test
    fun `an unowned untracked source cannot be admitted as generated`() {
        val root = repositoryRoot()
        val relativePath = "outside-build-ownership/Generated.kt"
        val sourcePath = root.resolve(relativePath)
        Files.createDirectories(sourcePath.parent)
        Files.writeString(sourcePath, "class Generated")

        assertRejected(
            RepositoryOperationRejection.SourceStateConflict(relativePath),
            validInput(
                root,
                sourceState = validSourceState(
                    inputs = listOf(
                        includedSource(
                            relativePath,
                            RawSourceInputKind.GENERATED,
                            sha256(sourcePath),
                        ),
                    ),
                    root = root,
                ),
            ),
        )
    }

    @Test
    fun `path bounds apply to the combined source inventory`() {
        val root = repositoryRoot()
        val trackedRelativePath = "misleading-layout/src/Application.kt"
        val untrackedRelativePath = "misleading-layout/src/New.kt"
        val trackedPath = root.resolve(trackedRelativePath)
        val untrackedPath = root.resolve(untrackedRelativePath)
        Files.writeString(trackedPath, "package sample\nclass ChangedApplication\n")
        Files.writeString(untrackedPath, "package sample\nclass New\n")

        assertRejected(
            RepositoryOperationRejection.ResourceBoundExceeded(ResourceBoundKind.PATHS),
            validInput(
                root,
                sourceState = validSourceState(
                    inputs = listOf(
                        includedSource(
                            trackedRelativePath,
                            RawSourceInputKind.TRACKED_CHANGE,
                            sha256(trackedPath),
                        ),
                        includedSource(
                            untrackedRelativePath,
                            RawSourceInputKind.UNTRACKED,
                            sha256(untrackedPath),
                        ),
                    ),
                    root = root,
                ),
                resourceBounds = validBounds().copy(pathLimit = 1),
            ),
        )
    }

    @Test
    fun `source state movement during admission is rejected`() {
        val contentRoot = repositoryRoot("content-movement")
        val trackedRelativePath = "misleading-layout/src/Application.kt"
        val trackedPath = contentRoot.resolve(trackedRelativePath)
        Files.writeString(trackedPath, "package sample\nclass Initial\n")
        val contentInput = validInput(
            contentRoot,
            sourceState = validSourceState(
                inputs = listOf(
                    includedSource(
                        trackedRelativePath,
                        RawSourceInputKind.TRACKED_CHANGE,
                        sha256(trackedPath),
                    ),
                ),
                root = contentRoot,
            ),
        )
        assertRejectedResult(
            RepositoryOperationRejection.SourceStateConflict(trackedRelativePath),
            RepositoryOperationAdmissionParser(
                contentInput,
                SourceStateStabilityCheckpoint {
                    Files.writeString(trackedPath, "package sample\nclass Moved\n")
                },
            ).parse(),
        )

        val inventoryRoot = repositoryRoot("inventory-movement")
        val lateRelativePath = "misleading-layout/src/Late.kt"
        assertRejectedResult(
            RepositoryOperationRejection.SourceStateConflict(lateRelativePath),
            RepositoryOperationAdmissionParser(
                validInput(inventoryRoot),
                SourceStateStabilityCheckpoint {
                    Files.writeString(inventoryRoot.resolve(lateRelativePath), "class Late")
                },
            ).parse(),
        )

        val revisionRoot = repositoryRoot("revision-movement")
        val admittedRevision = git(revisionRoot, "rev-parse", "HEAD")
        assertRejectedResult(
            RepositoryOperationRejection.SourceRevisionUnresolvable(admittedRevision),
            RepositoryOperationAdmissionParser(
                validInput(revisionRoot),
                SourceStateStabilityCheckpoint {
                    git(revisionRoot, "commit", "--quiet", "--allow-empty", "-m", "move revision")
                },
            ).parse(),
        )
    }

    @Test
    fun `source content reads do not follow a replacement symlink`() {
        val root = repositoryRoot("content-symlink-race")
        val relativePath = "misleading-layout/src/Application.kt"
        val sourcePath = root.resolve(relativePath)
        val outsidePath = temporaryDirectory.resolve("outside-same-content.kt")
        val content = "package sample\nclass Dirty\n"
        Files.writeString(sourcePath, content)
        Files.writeString(outsidePath, content)
        val input = validInput(
            root,
            sourceState = validSourceState(
                inputs = listOf(
                    includedSource(relativePath, RawSourceInputKind.TRACKED_CHANGE, sha256(sourcePath)),
                ),
                root = root,
            ),
        )
        var reads = 0

        assertRejectedResult(
            RepositoryOperationRejection.SourceStateConflict(relativePath),
            RepositoryOperationAdmissionParser(
                rawInput = input,
                contentReadCheckpoint = SourceContentReadCheckpoint {
                    reads += 1
                    if (reads == 2) {
                        Files.delete(sourcePath)
                        Files.createSymbolicLink(sourcePath, outsidePath)
                    }
                },
            ).parse(),
        )
    }

    @Test
    fun `excluded source observations do not follow a replacement symlink`() {
        val root = repositoryRoot("excluded-symlink-race")
        val relativePath = "misleading-layout/src/Application.kt"
        val sourcePath = root.resolve(relativePath)
        val outsidePath = temporaryDirectory.resolve("outside-excluded.kt")
        Files.writeString(sourcePath, "package sample\nclass Dirty\n")
        Files.writeString(outsidePath, "package external\nclass External\n")
        val input = validInput(
            root,
            sourceState = validSourceState(
                inputs = listOf(
                    RawSourceInput(
                        path = relativePath,
                        kind = RawSourceInputKind.TRACKED_CHANGE,
                        presence = RawSourceInputPresence.PRESENT,
                        disposition = RawSourceInputDisposition.EXCLUDED,
                        contentSha256 = null,
                    ),
                ),
                root = root,
            ),
        )

        assertRejectedResult(
            RepositoryOperationRejection.RepositoryPathOutsideRoot(relativePath),
            RepositoryOperationAdmissionParser(
                rawInput = input,
                stabilityCheckpoint = SourceStateStabilityCheckpoint {
                    Files.delete(sourcePath)
                    Files.createSymbolicLink(sourcePath, outsidePath)
                },
            ).parse(),
        )
    }
}
