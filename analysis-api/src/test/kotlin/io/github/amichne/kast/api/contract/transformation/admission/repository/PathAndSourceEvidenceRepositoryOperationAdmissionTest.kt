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

class PathAndSourceEvidenceRepositoryOperationAdmissionTest : RepositoryOperationAdmissionFixture() {
    @Test
    fun `unresolvable roots are rejected before repository state exists`() {
        val input = validInput()
        assertRejected(
            RepositoryOperationRejection.RepositoryRootUnresolvable("missing"),
            input.copy(repository = RawRepositoryInput("missing", null)),
        )
        val nonRepository = temporaryDirectory.resolve("not-a-repository")
        Files.createDirectories(nonRepository)
        assertRejected(
            RepositoryOperationRejection.RepositoryRootUnresolvable(nonRepository.toString()),
            input.copy(repository = RawRepositoryInput(nonRepository.toString(), temporaryDirectory.toString())),
        )
        val fakeRepository = temporaryDirectory.resolve("fake-repository")
        Files.createDirectories(fakeRepository.resolve(".git"))
        assertRejected(
            RepositoryOperationRejection.RepositoryRootUnresolvable(fakeRepository.toString()),
            input.copy(
                repository = RawRepositoryInput(fakeRepository.toString(), temporaryDirectory.toString()),
            ),
        )
    }

    @Test
    fun `source paths cannot escape through traversal or symlink`() {
        val root = repositoryRoot()
        val outside = temporaryDirectory.resolve("outside")
        Files.createDirectories(outside)
        Files.writeString(outside.resolve("Outside.kt"), "class Outside")
        Files.createSymbolicLink(root.resolve("escape"), outside)
        val paths = listOf("../outside/Outside.kt", "escape/Outside.kt")

        paths.forEach { path ->
            assertRejected(
                RepositoryOperationRejection.RepositoryPathOutsideRoot(path),
                validInput(
                    root = root,
                    sourceState = validSourceState(
                        inputs = listOf(includedSource(path, RawSourceInputKind.TRACKED_CHANGE)),
                    ),
                ),
            )
        }
    }

    @Test
    fun `broken symlink ancestors cannot defer repository escape`() {
        val root = repositoryRoot()
        val futureOutsideTarget = temporaryDirectory.resolve("future-outside")
        Files.createSymbolicLink(root.resolve("broken"), futureOutsideTarget)
        val path = "broken/Future.kt"

        assertRejected(
            RepositoryOperationRejection.RepositoryPathOutsideRoot(path),
            validInput(
                root = root,
                sourceState = validSourceState(
                    inputs = listOf(includedSource(path, RawSourceInputKind.GENERATED)),
                ),
            ),
        )
    }

    @Test
    fun `an untracked source symlink cannot borrow a tracked path identity`() {
        val root = repositoryRoot("source-alias")
        val alias = "misleading-layout/src/Alias.kt"
        val trackedSource = root.resolve("misleading-layout/src/Application.kt")
        Files.createSymbolicLink(root.resolve(alias), Path.of("Application.kt"))

        assertRejected(
            RepositoryOperationRejection.RepositoryPathOutsideRoot(alias),
            validInput(
                root,
                sourceState = validSourceState(
                    inputs = listOf(
                        includedSource(alias, RawSourceInputKind.UNTRACKED, sha256(trackedSource)),
                    ),
                    root = root,
                ),
            ),
        )
    }

    @Test
    fun `build source roots and declarations cannot escape through symlinks`() {
        val root = repositoryRoot()
        val outside = temporaryDirectory.resolve("build-outside")
        Files.createDirectories(outside)
        Files.writeString(outside.resolve("Outside.kt"), "class Outside")
        Files.writeString(
            root.resolve(".git/info/exclude"),
            "\nbuild-escape\nmisleading-layout/src/escape\n",
            java.nio.file.StandardOpenOption.APPEND,
        )
        Files.createSymbolicLink(root.resolve("build-escape"), outside)
        assertRejected(
            RepositoryOperationRejection.RepositoryPathOutsideRoot("build-escape"),
            validInput(
                root = root,
                buildOwnership = available(
                    validCompilationUnit().copy(sourceRoots = setOf("build-escape")),
                ),
            ),
        )

        val sourceRoot = root.resolve("misleading-layout/src")
        Files.createSymbolicLink(sourceRoot.resolve("escape"), outside)
        val declarationPath = "misleading-layout/src/escape/Outside.kt"
        assertRejected(
            RepositoryOperationRejection.RepositoryPathOutsideRoot(declarationPath),
            validInput(
                root = root,
                buildOwnership = available(
                    validCompilationUnit().copy(
                        declarations = listOf(
                            RawOwnedDeclarationInput("outside.Outside", declarationPath),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `source state requires exact revision classification and content evidence`() {
        val root = repositoryRoot()
        val valid = validInput(root)
        assertRejected(
            RepositoryOperationRejection.SourceRevisionUnresolvable("main"),
            valid.copy(sourceState = validSourceState().copy(revision = "main")),
        )
        assertRejected(
            RepositoryOperationRejection.SourceRevisionUnresolvable(NONEXISTENT_REVISION),
            valid.copy(sourceState = validSourceState(root = root).copy(revision = NONEXISTENT_REVISION)),
        )
        assertRejected(
            RepositoryOperationRejection.SourceStateEvidenceMissing(
                SourceStateEvidenceKind.INVENTORY,
                null,
            ),
            valid.copy(sourceState = validSourceState(root = root).copy(inputs = null)),
        )
        val cases = listOf(
            RawSourceInput(
                path = null,
                kind = RawSourceInputKind.UNTRACKED,
                presence = RawSourceInputPresence.PRESENT,
                disposition = RawSourceInputDisposition.INCLUDED,
                contentSha256 = DIGEST_A,
            ) to
                RepositoryOperationRejection.SourceStateEvidenceMissing(SourceStateEvidenceKind.PATH, null),
            RawSourceInput(
                path = "src/New.kt",
                kind = null,
                presence = RawSourceInputPresence.PRESENT,
                disposition = RawSourceInputDisposition.INCLUDED,
                contentSha256 = DIGEST_A,
            ) to
                RepositoryOperationRejection.SourceStateEvidenceMissing(
                    SourceStateEvidenceKind.KIND,
                    "src/New.kt",
                ),
            RawSourceInput(
                path = "src/New.kt",
                kind = RawSourceInputKind.UNTRACKED,
                presence = null,
                disposition = RawSourceInputDisposition.INCLUDED,
                contentSha256 = DIGEST_A,
            ) to RepositoryOperationRejection.SourceStateEvidenceMissing(
                SourceStateEvidenceKind.PRESENCE,
                "src/New.kt",
            ),
            RawSourceInput(
                path = "src/New.kt",
                kind = RawSourceInputKind.UNTRACKED,
                presence = RawSourceInputPresence.PRESENT,
                disposition = null,
                contentSha256 = DIGEST_A,
            ) to
                RepositoryOperationRejection.SourceStateEvidenceMissing(
                    SourceStateEvidenceKind.DISPOSITION,
                    "src/New.kt",
                ),
            RawSourceInput(
                "src/New.kt",
                RawSourceInputKind.UNTRACKED,
                RawSourceInputPresence.PRESENT,
                RawSourceInputDisposition.INCLUDED,
                null,
            ) to RepositoryOperationRejection.SourceStateEvidenceMissing(
                SourceStateEvidenceKind.CONTENT_DIGEST,
                "src/New.kt",
            ),
        )
        cases.forEach { (sourceInput, expected) ->
            assertRejected(
                expected,
                valid.copy(sourceState = validSourceState(listOf(sourceInput))),
            )
        }
        assertRejected(
            RepositoryOperationRejection.SourceStateConflict("src/New.kt"),
            valid.copy(
                sourceState = validSourceState(
                    listOf(
                        includedSource("src/New.kt", RawSourceInputKind.UNTRACKED),
                        RawSourceInput(
                            path = "src/New.kt",
                            kind = RawSourceInputKind.UNTRACKED,
                            presence = RawSourceInputPresence.PRESENT,
                            disposition = RawSourceInputDisposition.EXCLUDED,
                            contentSha256 = null,
                        ),
                    ),
                ),
            ),
        )
        assertRejected(
            RepositoryOperationRejection.SourceStateConflict("src/Excluded.kt"),
            valid.copy(
                sourceState = validSourceState(
                    root = root,
                    inputs = listOf(
                        RawSourceInput(
                            path = "src/Excluded.kt",
                            kind = RawSourceInputKind.GENERATED,
                            presence = RawSourceInputPresence.PRESENT,
                            disposition = RawSourceInputDisposition.EXCLUDED,
                            contentSha256 = DIGEST_A,
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `dirty source identity includes tracked untracked and generated inputs`() {
        val root = repositoryRoot()
        val trackedRelativePath = "misleading-layout/src/Application.kt"
        val untrackedRelativePath = "src/New.kt"
        val generatedRelativePath = "misleading-layout/src/generated/Generated.kt"
        val trackedPath = root.resolve(trackedRelativePath)
        val untrackedPath = root.resolve(untrackedRelativePath)
        val generatedPath = root.resolve(generatedRelativePath)
        Files.writeString(trackedPath, "package sample\nclass Application(val first: Boolean)\n")
        Files.createDirectories(untrackedPath.parent)
        Files.writeString(untrackedPath, "class NewOne")
        Files.createDirectories(generatedPath.parent)
        Files.writeString(generatedPath, "class Generated")
        var inputs = listOf(
            includedSource(trackedRelativePath, RawSourceInputKind.TRACKED_CHANGE, sha256(trackedPath)),
            includedSource(untrackedRelativePath, RawSourceInputKind.UNTRACKED, sha256(untrackedPath)),
            RawSourceInput(
                path = generatedRelativePath,
                kind = RawSourceInputKind.GENERATED,
                presence = RawSourceInputPresence.PRESENT,
                disposition = RawSourceInputDisposition.EXCLUDED,
                contentSha256 = null,
            ),
        )
        val sourceState = validSourceState(inputs = inputs, root = root)
        val admitted = admitted(validInput(root, sourceState = sourceState))

        assertEquals(3, admitted.repositoryState.sourceState.inputs.size)
        assertEquals(
            listOf(
                "$trackedRelativePath|TRACKED_CHANGE|PRESENT|INCLUDED|${sha256(trackedPath)}",
                "$generatedRelativePath|GENERATED|PRESENT|EXCLUDED|",
                "$untrackedRelativePath|UNTRACKED|PRESENT|INCLUDED|${sha256(untrackedPath)}",
            ),
            admitted.repositoryState.sourceState.inputs.map { input ->
                listOf(
                    input.path.value,
                    input.kind.name,
                    input.presence.name,
                    input.disposition.name,
                    input.contentDigest?.value.orEmpty(),
                ).joinToString("|")
            },
        )
        Files.writeString(trackedPath, "package sample\nclass Application(val second: Boolean)\n")
        inputs = inputs.map { input ->
            if (input.kind == RawSourceInputKind.TRACKED_CHANGE) {
                input.copy(contentSha256 = sha256(trackedPath))
            } else {
                input
            }
        }
        val changedTracked = admitted(
            validInput(root, sourceState = validSourceState(inputs = inputs, root = root)),
        )
        assertNotEquals(admitted.repositoryState.identity, changedTracked.repositoryState.identity)

        Files.writeString(untrackedPath, "class NewTwo")
        inputs = inputs.map { input ->
            if (input.kind == RawSourceInputKind.UNTRACKED) {
                input.copy(contentSha256 = sha256(untrackedPath))
            } else {
                input
            }
        }
        val changedUntracked = admitted(
            validInput(root, sourceState = validSourceState(inputs = inputs, root = root)),
        )
        assertNotEquals(changedTracked.repositoryState.identity, changedUntracked.repositoryState.identity)

        inputs = inputs.map { input ->
            if (input.kind == RawSourceInputKind.GENERATED) {
                input.copy(
                    disposition = RawSourceInputDisposition.INCLUDED,
                    contentSha256 = sha256(generatedPath),
                )
            } else {
                input
            }
        }
        val changedGeneratedAdmission = admitted(
            validInput(root, sourceState = validSourceState(inputs = inputs, root = root)),
        )
        assertNotEquals(changedUntracked.repositoryState.identity, changedGeneratedAdmission.repositoryState.identity)

        git(root, "commit", "--quiet", "--allow-empty", "-m", "second source revision")
        val changedRevision = admitted(
            validInput(root, sourceState = validSourceState(inputs = inputs, root = root)),
        )
        assertNotEquals(admitted.repositoryState.identity, changedRevision.repositoryState.identity)
    }
}
