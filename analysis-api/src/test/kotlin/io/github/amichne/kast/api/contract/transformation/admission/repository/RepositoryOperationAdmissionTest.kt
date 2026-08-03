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

class RepositoryOperationAdmissionTest {
    @TempDir
    lateinit var temporaryDirectory: Path

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

    @Test
    fun `each semantic compiler input changes repository state identity`() {
        val root = repositoryRoot()
        val unit = validCompilationUnit()
        val compiler = requireNotNull(unit.compiler)
        val baseline = admitted(validInput(root, buildOwnership = available(unit))).repositoryState.identity
        val variants = listOf(
            unit.copy(compiler = compiler.copy(compilerVersion = "2.4.0")),
            unit.copy(compiler = compiler.copy(languageVersion = "2.2")),
            unit.copy(compiler = compiler.copy(apiVersion = "2.2")),
            unit.copy(compiler = compiler.copy(languageSettings = mapOf("progressive" to "false"))),
            unit.copy(
                compiler = compiler.copy(
                    compilerImplementation = requireNotNull(compiler.compilerImplementation).copy(
                        contentSha256 = DIGEST_B,
                    ),
                ),
            ),
            unit.copy(
                compiler = compiler.copy(
                    toolchain = requireNotNull(compiler.toolchain).copy(contentSha256 = DIGEST_B),
                ),
            ),
            unit.copy(
                compiler = compiler.copy(
                    compilerOptions = listOf(compilerOption("-progressive"), compilerOption("-Xcontext-receivers")),
                ),
            ),
            unit.copy(
                compiler = compiler.copy(
                    resolvedDependencies = listOf(
                        resolvedArtifact(
                            component = "org.example:library:1.0",
                            contentSha256 = DIGEST_B,
                        ),
                    ),
                ),
            ),
            unit.copy(
                compiler = compiler.copy(
                    compilerPlugins = listOf(compilerPlugin(artifactSha256 = DIGEST_B)),
                ),
            ),
            unit.copy(
                compiler = compiler.copy(
                    compilerPlugins = listOf(
                        compilerPlugin(options = listOf("enabled=false")),
                    ),
                ),
            ),
            unit.copy(moduleIdentity = ":included:application"),
            unit.copy(sourceSetName = "commonMain"),
            unit.copy(variantName = "release"),
            unit.copy(
                sourceRoots = setOf("alternate/src"),
                generatedSourceRoots = setOf("alternate/src/generated"),
                declarations = listOf(
                    RawOwnedDeclarationInput("sample.Application", "alternate/src/Application.kt"),
                ),
            ),
            unit.copy(
                declarations = listOf(
                    RawOwnedDeclarationInput("sample.Alternate", "misleading-layout/src/Alternate.kt"),
                ),
            ),
            unit.copy(families = setOf("sample.Alternate")),
            unit.copy(generatedSourceRoots = setOf("misleading-layout/src/other-generated")),
        )

        variants.forEach { changedUnit ->
            val changed = admitted(
                validInput(root, buildOwnership = available(changedUnit)),
            ).repositoryState.identity
            assertNotEquals(baseline, changed, "semantic input must change repository state identity")
        }
    }

    @Test
    fun `repository identity is permutation invariant across complete evidence`() {
        val root = repositoryRoot()
        val declarations = listOf(
            RawOwnedDeclarationInput("sample.Duplicate", "misleading-layout/src/A.kt"),
            RawOwnedDeclarationInput("sample.Duplicate", "misleading-layout/src/B.kt"),
        )
        val firstUnit = validCompilationUnit().copy(
            declarations = declarations,
            families = linkedSetOf("sample.Second", "sample.First"),
            compiler = validCompiler().copy(
                languageSettings = linkedMapOf("second" to "2", "first" to "1"),
                resolvedDependencies = listOf(
                    resolvedArtifact("dependency:b", contentSha256 = DIGEST_B),
                    resolvedArtifact("dependency:a", contentSha256 = DIGEST_A),
                ),
                compilerPlugins = listOf(
                    compilerPlugin("plugin:b", artifactSha256 = DIGEST_B),
                    compilerPlugin("plugin:a", artifactSha256 = DIGEST_A),
                ),
            ),
        )
        val secondUnit = validCompilationUnit(
            ownerId = "unit-test",
            sourceSetName = "jvmTest",
            compiler = requireNotNull(firstUnit.compiler),
        ).copy(
            declarations = declarations,
            families = firstUnit.families,
        )
        val firstCompiler = requireNotNull(firstUnit.compiler)
        val firstSourcePath = root.resolve("src/A.kt")
        val secondSourcePath = root.resolve("src/B.kt")
        Files.createDirectories(firstSourcePath.parent)
        Files.writeString(firstSourcePath, "class A")
        Files.writeString(secondSourcePath, "class B")
        val sourceInputs = listOf(
            includedSource("src/B.kt", RawSourceInputKind.UNTRACKED, sha256(secondSourcePath)),
            includedSource("src/A.kt", RawSourceInputKind.UNTRACKED, sha256(firstSourcePath)),
        )
        val forward = admitted(
            validInput(
                root = root,
                sourceState = validSourceState(inputs = sourceInputs, root = root),
                buildOwnership = available(firstUnit, secondUnit),
            ),
        )
        val reverseFirst = firstUnit.copy(
            declarations = declarations.reversed(),
            families = firstUnit.families?.reversed()?.toSet(),
            compiler = firstCompiler.copy(
                languageSettings = firstCompiler.languageSettings?.entries
                    ?.reversed()
                    ?.associate { entry -> entry.key to entry.value },
                resolvedDependencies = firstCompiler.resolvedDependencies,
                compilerPlugins = firstCompiler.compilerPlugins,
            ),
        )
        val reverseSecond = secondUnit.copy(
            declarations = declarations.reversed(),
            families = secondUnit.families?.reversed()?.toSet(),
        )
        val reversed = admitted(
            validInput(
                root = root,
                sourceState = validSourceState(inputs = sourceInputs.reversed(), root = root),
                buildOwnership = available(reverseSecond, reverseFirst),
            ),
        )

        assertEquals(forward.repositoryState.identity, reversed.repositoryState.identity)
    }

    @Test
    fun `repository identity preserves semantic field boundaries`() {
        val root = repositoryRoot()
        val first = validCompilationUnit().copy(
            compiler = validCompiler().copy(languageSettings = mapOf("a=b" to "c")),
        )
        val second = validCompilationUnit().copy(
            compiler = validCompiler().copy(languageSettings = mapOf("a" to "b=c")),
        )

        val firstIdentity = admitted(validInput(root, buildOwnership = available(first))).repositoryState.identity
        val secondIdentity = admitted(validInput(root, buildOwnership = available(second))).repositoryState.identity

        assertNotEquals(firstIdentity, secondIdentity)
    }

    @Test
    fun `resolved dependency classpath order changes semantic configuration`() {
        val root = repositoryRoot()
        val forward = validCompilationUnit().copy(
            compiler = validCompiler().copy(
                resolvedDependencies = listOf(
                    resolvedArtifact("classpath:first", contentSha256 = DIGEST_A),
                    resolvedArtifact("classpath:second", contentSha256 = DIGEST_B),
                ),
            ),
        )
        val reversed = validCompilationUnit().copy(
            compiler = validCompiler().copy(
                resolvedDependencies = listOf(
                    resolvedArtifact("classpath:second", contentSha256 = DIGEST_B),
                    resolvedArtifact("classpath:first", contentSha256 = DIGEST_A),
                ),
            ),
        )

        val forwardIdentity = admitted(validInput(root, buildOwnership = available(forward)))
            .repositoryState
            .identity
        val reversedIdentity = admitted(validInput(root, buildOwnership = available(reversed)))
            .repositoryState
            .identity

        assertNotEquals(forwardIdentity, reversedIdentity)
    }

    @Test
    fun `compiler plugin order changes semantic configuration`() {
        val root = repositoryRoot()
        val forward = validCompilationUnit().copy(
            compiler = validCompiler().copy(
                compilerPlugins = listOf(
                    compilerPlugin("plugin:first", artifactSha256 = DIGEST_A),
                    compilerPlugin("plugin:second", artifactSha256 = DIGEST_B),
                ),
            ),
        )
        val reversed = validCompilationUnit().copy(
            compiler = validCompiler().copy(
                compilerPlugins = listOf(
                    compilerPlugin("plugin:second", artifactSha256 = DIGEST_B),
                    compilerPlugin("plugin:first", artifactSha256 = DIGEST_A),
                ),
            ),
        )

        val forwardIdentity = admitted(validInput(root, buildOwnership = available(forward)))
            .repositoryState
            .identity
        val reversedIdentity = admitted(validInput(root, buildOwnership = available(reversed)))
            .repositoryState
            .identity

        assertNotEquals(forwardIdentity, reversedIdentity)
    }

    @Test
    fun `compiler and plugin option order changes semantic configuration`() {
        val root = repositoryRoot()
        val compilerOptionsForward = validCompilationUnit().copy(
            compiler = validCompiler().copy(
                compilerOptions = listOf(compilerOption("-first"), compilerOption("-second")),
            ),
        )
        val compilerOptionsReversed = validCompilationUnit().copy(
            compiler = validCompiler().copy(
                compilerOptions = listOf(compilerOption("-second"), compilerOption("-first")),
            ),
        )
        val pluginOptionsForward = validCompilationUnit().copy(
            compiler = validCompiler().copy(
                compilerPlugins = listOf(compilerPlugin(options = listOf("first=1", "second=2"))),
            ),
        )
        val pluginOptionsReversed = validCompilationUnit().copy(
            compiler = validCompiler().copy(
                compilerPlugins = listOf(compilerPlugin(options = listOf("second=2", "first=1"))),
            ),
        )

        assertNotEquals(
            admitted(validInput(root, buildOwnership = available(compilerOptionsForward))).repositoryState.identity,
            admitted(validInput(root, buildOwnership = available(compilerOptionsReversed))).repositoryState.identity,
        )
        assertNotEquals(
            admitted(validInput(root, buildOwnership = available(pluginOptionsForward))).repositoryState.identity,
            admitted(validInput(root, buildOwnership = available(pluginOptionsReversed))).repositoryState.identity,
        )
    }

    @Test
    fun `incomplete build and compiler evidence is rejected`() {
        val unit = validCompilationUnit()
        val compiler = requireNotNull(unit.compiler)
        assertRejected(
            RepositoryOperationRejection.BuildOwnershipEvidenceUnavailable,
            validInput(buildOwnership = RawBuildOwnershipEvidence.Unavailable),
        )
        listOf<List<RawCompilationUnitInput>?>(null, emptyList()).forEach { units ->
            assertRejected(
                RepositoryOperationRejection.SemanticConfigurationIncomplete(
                    SemanticConfigurationField.COMPILATION_UNITS,
                    null,
                ),
                validInput(buildOwnership = RawBuildOwnershipEvidence.Available(units)),
            )
        }
        val cases = listOf(
            Triple(SemanticConfigurationField.OWNER_ID, null, unit.copy(ownerId = null)),
            Triple(
                SemanticConfigurationField.MODULE_IDENTITY,
                "unit-main",
                unit.copy(moduleIdentity = null),
            ),
            Triple(SemanticConfigurationField.MODULE, "unit-main", unit.copy(moduleName = null)),
            Triple(SemanticConfigurationField.SOURCE_SET, "unit-main", unit.copy(sourceSetName = null)),
            Triple(SemanticConfigurationField.VARIANT, "unit-main", unit.copy(variantName = null)),
            Triple(SemanticConfigurationField.SOURCE_ROOTS, "unit-main", unit.copy(sourceRoots = null)),
            Triple(SemanticConfigurationField.DECLARATIONS, "unit-main", unit.copy(declarations = null)),
            Triple(SemanticConfigurationField.FAMILIES, "unit-main", unit.copy(families = null)),
            Triple(
                SemanticConfigurationField.GENERATED_SOURCE_ROOTS,
                "unit-main",
                unit.copy(generatedSourceRoots = null),
            ),
            Triple(
                SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS,
                "unit-main",
                unit.copy(sourceSetRelationships = null),
            ),
            Triple(SemanticConfigurationField.COMPILER, "unit-main", unit.copy(compiler = null)),
            Triple(
                SemanticConfigurationField.COMPILER_VERSION,
                "unit-main",
                unit.copy(compiler = compiler.copy(compilerVersion = null)),
            ),
            Triple(
                SemanticConfigurationField.LANGUAGE_VERSION,
                "unit-main",
                unit.copy(compiler = compiler.copy(languageVersion = null)),
            ),
            Triple(
                SemanticConfigurationField.API_VERSION,
                "unit-main",
                unit.copy(compiler = compiler.copy(apiVersion = null)),
            ),
            Triple(
                SemanticConfigurationField.LANGUAGE_SETTINGS,
                "unit-main",
                unit.copy(compiler = compiler.copy(languageSettings = null)),
            ),
            Triple(
                SemanticConfigurationField.COMPILER_IMPLEMENTATION,
                "unit-main",
                unit.copy(compiler = compiler.copy(compilerImplementation = null)),
            ),
            Triple(
                SemanticConfigurationField.TOOLCHAIN,
                "unit-main",
                unit.copy(compiler = compiler.copy(toolchain = null)),
            ),
            Triple(
                SemanticConfigurationField.COMPILER_OPTIONS,
                "unit-main",
                unit.copy(compiler = compiler.copy(compilerOptions = null)),
            ),
            Triple(
                SemanticConfigurationField.DEPENDENCIES,
                "unit-main",
                unit.copy(compiler = compiler.copy(resolvedDependencies = null)),
            ),
            Triple(
                SemanticConfigurationField.COMPILER_PLUGINS,
                "unit-main",
                unit.copy(compiler = compiler.copy(compilerPlugins = null)),
            ),
            Triple(
                SemanticConfigurationField.COMPILER_IMPLEMENTATION,
                "unit-main",
                unit.copy(
                    compiler = compiler.copy(
                        compilerImplementation = requireNotNull(compiler.compilerImplementation).copy(
                            contentSha256 = null,
                        ),
                    ),
                ),
            ),
            Triple(
                SemanticConfigurationField.TOOLCHAIN,
                "unit-main",
                unit.copy(
                    compiler = compiler.copy(
                        toolchain = requireNotNull(compiler.toolchain).copy(contentSha256 = null),
                    ),
                ),
            ),
            Triple(
                SemanticConfigurationField.DEPENDENCIES,
                "unit-main",
                unit.copy(
                    compiler = compiler.copy(
                        resolvedDependencies = listOf(resolvedArtifact(contentSha256 = null)),
                    ),
                ),
            ),
            Triple(
                SemanticConfigurationField.COMPILER_PLUGINS,
                "unit-main",
                unit.copy(
                    compiler = compiler.copy(
                        compilerPlugins = listOf(compilerPlugin().copy(options = null)),
                    ),
                ),
            ),
        )
        cases.forEach { (field, owner, incompleteUnit) ->
            assertRejected(
                RepositoryOperationRejection.SemanticConfigurationIncomplete(field, owner),
                validInput(buildOwnership = available(incompleteUnit)),
            )
        }
    }

    @Test
    fun `source set resolution relationships are exact and build qualified`() {
        val root = repositoryRoot()
        val main = validCompilationUnit()
        val common = validCompilationUnit(
            ownerId = "unit-common",
            sourceSetName = "commonMain",
        )
        val dependsOn = main.copy(
            sourceSetRelationships = setOf(
                sourceSetRelationship(SourceSetRelationshipKind.DEPENDS_ON, "unit-common"),
            ),
        )
        val friend = main.copy(
            sourceSetRelationships = setOf(
                sourceSetRelationship(SourceSetRelationshipKind.FRIEND, "unit-common"),
            ),
        )
        val scope = listOf(RawScopeSelector.SourceSet(":application", "jvmMain"))
        val baselineIdentity = admitted(
            validInput(root, buildOwnership = available(main, common), scope = scope),
        ).repositoryState.identity
        val dependsOnIdentity = admitted(
            validInput(root, buildOwnership = available(dependsOn, common), scope = scope),
        ).repositoryState.identity
        val friendIdentity = admitted(
            validInput(root, buildOwnership = available(friend, common), scope = scope),
        ).repositoryState.identity

        assertNotEquals(baselineIdentity, dependsOnIdentity)
        assertNotEquals(dependsOnIdentity, friendIdentity)
        assertRejected(
            RepositoryOperationRejection.SemanticConfigurationIncomplete(
                SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS,
                "unit-main",
            ),
            validInput(
                root,
                buildOwnership = available(
                    main.copy(
                        sourceSetRelationships = setOf(
                            sourceSetRelationship(SourceSetRelationshipKind.DEPENDS_ON, "unit-missing"),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `scope includes the compatible transitive source set relationship closure`() {
        val main = validCompilationUnit(
            sourceSetRelationships = setOf(
                sourceSetRelationship(SourceSetRelationshipKind.DEPENDS_ON, "unit-common"),
            ),
        )
        val common = validCompilationUnit(
            ownerId = "unit-common",
            sourceSetName = "commonMain",
            sourceSetRelationships = setOf(
                sourceSetRelationship(SourceSetRelationshipKind.FRIEND, "unit-support"),
            ),
        )
        val support = validCompilationUnit(
            ownerId = "unit-support",
            sourceSetName = "supportMain",
        )

        val operation = admitted(
            validInput(
                buildOwnership = available(main, common, support),
                scope = listOf(RawScopeSelector.SourceSet(":application", "jvmMain")),
            ),
        )

        assertEquals(
            listOf("unit-common", "unit-main", "unit-support"),
            operation.resolvedScope.compilationUnits.map { unit -> unit.id.value },
        )
    }

    @Test
    fun `source set relationship cycles and incompatible closure are rejected`() {
        val main = validCompilationUnit(
            sourceSetRelationships = setOf(
                sourceSetRelationship(SourceSetRelationshipKind.DEPENDS_ON, "unit-common"),
            ),
        )
        val cyclicCommon = validCompilationUnit(
            ownerId = "unit-common",
            sourceSetName = "commonMain",
            sourceSetRelationships = setOf(
                sourceSetRelationship(SourceSetRelationshipKind.DEPENDS_ON, "unit-main"),
            ),
        )
        assertRejected(
            RepositoryOperationRejection.SemanticConfigurationIncomplete(
                SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS,
                "unit-main",
            ),
            validInput(buildOwnership = available(main, cyclicCommon)),
        )

        val incompatibleCommon = validCompilationUnit(
            ownerId = "unit-common",
            sourceSetName = "commonMain",
            compiler = validCompiler().copy(compilerVersion = "2.4.0"),
        )
        assertRejected(
            RepositoryOperationRejection.IncompatibleSemanticConfigurations(
                listOf("unit-common", "unit-main"),
            ),
            validInput(
                buildOwnership = available(main, incompatibleCommon),
                scope = listOf(RawScopeSelector.SourceSet(":application", "jvmMain")),
            ),
        )
    }

    @Test
    fun `all scope selector kinds resolve mechanically or reject unknown input`() {
        val validSelectors = listOf(
            RawScopeSelector.Module("application"),
            RawScopeSelector.SourceSet("application", "jvmMain"),
            RawScopeSelector.Declaration("sample.Application"),
            RawScopeSelector.Family("sample.Application"),
        )
        validSelectors.forEach { selector ->
            val operation = admitted(validInput(scope = listOf(selector)))
            assertEquals(listOf("unit-main"), operation.resolvedScope.compilationUnits.map { it.id.value })
        }
        val unknownSelectors = listOf(
            RawScopeSelector.Module("missing"),
            RawScopeSelector.SourceSet("application", "missing"),
            RawScopeSelector.Declaration("missing"),
            RawScopeSelector.Family("missing"),
        )
        unknownSelectors.forEach { selector ->
            assertRejected(
                RepositoryOperationRejection.UnknownScope(selector),
                validInput(scope = listOf(selector)),
            )
        }
        assertRejected(
            RepositoryOperationRejection.ScopeResolvesToNothing,
            validInput(scope = emptyList()),
        )
    }

    @Test
    fun `singular scope selectors reject ambiguous build ownership`() {
        val first = validCompilationUnit()
        val second = validCompilationUnit(
            ownerId = "unit-other",
        )
        val selectors = listOf(
            RawScopeSelector.SourceSet("application", "jvmMain"),
            RawScopeSelector.Declaration("sample.Application"),
            RawScopeSelector.Family("sample.Application"),
        )
        selectors.forEach { selector ->
            val candidates = if (selector is RawScopeSelector.Declaration) {
                listOf(
                    "unit-main:misleading-layout/src/Application.kt",
                    "unit-other:misleading-layout/src/Application.kt",
                )
            } else {
                listOf("unit-main", "unit-other")
            }
            assertRejected(
                RepositoryOperationRejection.AmbiguousScope(
                    selector = selector,
                    candidates = candidates,
                ),
                validInput(
                    buildOwnership = available(first, second),
                    scope = listOf(selector),
                ),
            )
        }
    }

    @Test
    fun `a declaration selector rejects duplicate occurrences inside one compilation unit`() {
        val selector = RawScopeSelector.Declaration("sample.Duplicate")
        val unit = validCompilationUnit().copy(
            declarations = listOf(
                RawOwnedDeclarationInput(
                    "sample.Duplicate",
                    "misleading-layout/src/FirstDuplicate.kt",
                ),
                RawOwnedDeclarationInput(
                    "sample.Duplicate",
                    "misleading-layout/src/SecondDuplicate.kt",
                ),
            ),
        )

        assertRejected(
            RepositoryOperationRejection.AmbiguousScope(
                selector = selector,
                candidates = listOf(
                    "unit-main:misleading-layout/src/FirstDuplicate.kt",
                    "unit-main:misleading-layout/src/SecondDuplicate.kt",
                ),
            ),
            validInput(
                buildOwnership = available(unit),
                scope = listOf(selector),
            ),
        )
    }

    @Test
    fun `module scope distinguishes build-qualified authority from ambiguous display names`() {
        val first = validCompilationUnit(
            ownerId = "included-one-main",
            moduleIdentity = ":included-one:application",
        )
        val second = validCompilationUnit(
            ownerId = "included-two-main",
            moduleIdentity = ":included-two:application",
        )
        val ambiguous = RawScopeSelector.Module("application")
        assertRejected(
            RepositoryOperationRejection.AmbiguousScope(
                selector = ambiguous,
                candidates = listOf(":included-one:application", ":included-two:application"),
            ),
            validInput(
                buildOwnership = available(first, second),
                scope = listOf(ambiguous),
            ),
        )

        val exact = admitted(
            validInput(
                buildOwnership = available(first, second),
                scope = listOf(RawScopeSelector.Module(":included-one:application")),
            ),
        )
        assertEquals(
            listOf("included-one-main"),
            exact.resolvedScope.compilationUnits.map { unit -> unit.id.value },
        )
    }

    @Test
    fun `one module identity cannot carry conflicting display names`() {
        val first = validCompilationUnit()
        val second = validCompilationUnit(
            ownerId = "unit-test",
            moduleName = "other-application",
            sourceSetName = "jvmTest",
        )

        assertRejected(
            RepositoryOperationRejection.SemanticConfigurationIncomplete(
                SemanticConfigurationField.MODULE_IDENTITY,
                ":application",
            ),
            validInput(
                buildOwnership = available(first, second),
                scope = listOf(RawScopeSelector.Module(":application")),
            ),
        )
    }

    @Test
    fun `module selectors reject collisions between identity and display namespaces`() {
        val identityMatch = validCompilationUnit(
            ownerId = "identity-main",
            moduleIdentity = "application",
            moduleName = "identity-module",
        )
        val displayMatch = validCompilationUnit(
            ownerId = "display-main",
            moduleIdentity = ":display-module",
            moduleName = "application",
        )
        val selector = RawScopeSelector.Module("application")

        assertRejected(
            RepositoryOperationRejection.AmbiguousScope(
                selector = selector,
                candidates = listOf(":display-module", "application"),
            ),
            validInput(
                buildOwnership = available(identityMatch, displayMatch),
                scope = listOf(selector),
            ),
        )
    }

    @Test
    fun `Gradle ownership outranks misleading directory layout`() {
        val operation = admitted(
            validInput(scope = listOf(RawScopeSelector.Declaration("sample.Application"))),
        )

        val owner = operation.resolvedScope.compilationUnits.single()
        assertEquals("application", owner.moduleName.value)
        assertEquals("jvmMain", owner.sourceSetName.value)
        assertEquals("misleading-layout/src/Application.kt", owner.declarations.single().path.value)
    }

    @Test
    fun `incompatible configurations are rejected before analysis`() {
        val first = validCompilationUnit()
        val second = validCompilationUnit(
            ownerId = "unit-test",
            sourceSetName = "jvmTest",
            compiler = validCompiler().copy(compilerVersion = "2.4.0"),
        )

        assertRejected(
            RepositoryOperationRejection.IncompatibleSemanticConfigurations(
                listOf("unit-main", "unit-test"),
            ),
            validInput(
                buildOwnership = available(first, second),
                scope = listOf(RawScopeSelector.Module("application")),
            ),
        )

        val artifactConflict = validCompilationUnit(
            ownerId = "unit-artifact-conflict",
            sourceSetName = "jvmTest",
            compiler = validCompiler().copy(
                resolvedDependencies = listOf(resolvedArtifact(contentSha256 = DIGEST_B)),
            ),
        )
        assertRejected(
            RepositoryOperationRejection.IncompatibleSemanticConfigurations(
                listOf("unit-artifact-conflict", "unit-main"),
            ),
            validInput(
                buildOwnership = available(first, artifactConflict),
                scope = listOf(RawScopeSelector.Module("application")),
            ),
        )

        val pluginConflict = validCompilationUnit(
            ownerId = "unit-plugin-conflict",
            sourceSetName = "jvmTest",
            compiler = validCompiler().copy(
                compilerPlugins = listOf(compilerPlugin(options = listOf("enabled=false"))),
            ),
        )
        assertRejected(
            RepositoryOperationRejection.IncompatibleSemanticConfigurations(
                listOf("unit-main", "unit-plugin-conflict"),
            ),
            validInput(
                buildOwnership = available(first, pluginConflict),
                scope = listOf(RawScopeSelector.Module("application")),
            ),
        )

        val toolchainConflict = validCompilationUnit(
            ownerId = "unit-toolchain-conflict",
            sourceSetName = "jvmTest",
            compiler = validCompiler().let { compiler ->
                compiler.copy(
                    toolchain = requireNotNull(compiler.toolchain).copy(contentSha256 = DIGEST_B),
                )
            },
        )
        assertRejected(
            RepositoryOperationRejection.IncompatibleSemanticConfigurations(
                listOf("unit-main", "unit-toolchain-conflict"),
            ),
            validInput(
                buildOwnership = available(first, toolchainConflict),
                scope = listOf(RawScopeSelector.Module("application")),
            ),
        )

        val dependencyConflict = validCompilationUnit(
            ownerId = "unit-dependency-conflict",
            sourceSetName = "jvmTest",
            compiler = validCompiler().copy(
                resolvedDependencies = listOf(
                    resolvedArtifact("org.example:library:2.0", contentSha256 = DIGEST_B),
                ),
            ),
        )
        assertRejected(
            RepositoryOperationRejection.IncompatibleSemanticConfigurations(
                listOf("unit-dependency-conflict", "unit-main"),
            ),
            validInput(
                buildOwnership = available(first, dependencyConflict),
                scope = listOf(RawScopeSelector.Module("application")),
            ),
        )
    }

    @Test
    fun `coherent multi-unit module scope retains every compilation unit`() {
        val first = validCompilationUnit()
        val second = validCompilationUnit(
            ownerId = "unit-test",
            sourceSetName = "jvmTest",
        )

        val operation = admitted(
            validInput(
                buildOwnership = available(first, second),
                scope = listOf(RawScopeSelector.Module("application")),
            ),
        )

        assertEquals(
            listOf("unit-main", "unit-test"),
            operation.resolvedScope.compilationUnits.map { unit -> unit.id.value },
        )
    }

    @Test
    fun `valid input exposes only trusted exact admission values`() {
        val root = repositoryRoot()
        val operation = admitted(validInput(root))

        assertEquals(root.toRealPath().toString(), operation.repositoryState.canonicalRoot.value)
        assertEquals(git(root, "rev-parse", "HEAD"), operation.repositoryState.sourceState.revision.value)
        assertEquals("application", operation.resolvedScope.compilationUnits.single().moduleName.value)
        assertEquals(
            "2.3.0",
            operation.repositoryState.semanticConfiguration.compilerVersion.value,
        )
        assertEquals(
            DIGEST_A,
            operation.repositoryState.semanticConfiguration.compilerImplementation.contentDigest.value,
        )
        assertEquals("jvm", operation.repositoryState.semanticConfiguration.toolchain.targetPlatform.value)
        assertEquals(30_000, operation.resourceBounds.timeLimitMillis.value)
        assertEquals(512L * 1024L * 1024L, operation.resourceBounds.memoryLimitBytes.value)
        assertEquals(32, operation.resourceBounds.traversalDepthLimit.value)
        assertEquals(10_000, operation.resourceBounds.pathLimit.value)
        assertEquals(1_000, operation.resourceBounds.resultLimit.value)
        assertTrue(operation.repositoryState.identity.value.isNotBlank())
    }

    @Test
    fun `trusted aggregate construction is owned by admission factories`() {
        val trustedAggregates = listOf(
            AdmittedRepositoryOperation::class.java,
            AdmittedRepositoryState::class.java,
            ExactSourceState::class.java,
            ExactSourceInput::class.java,
            ResolvedRepositoryScope::class.java,
            AdmittedCompilationUnit::class.java,
            AdmittedDeclaration::class.java,
            SourceSetRelationship::class.java,
            ResolvedBuildArtifact::class.java,
            CompilerToolchain::class.java,
            CompilerPluginInvocation::class.java,
            CoherentSemanticConfiguration::class.java,
            EstablishedResourceBounds::class.java,
        )

        trustedAggregates.forEach { trustedType ->
            assertTrue(
                trustedType.declaredConstructors
                    .filterNot { constructor -> constructor.isSynthetic }
                    .all { constructor -> Modifier.isPrivate(constructor.modifiers) },
                "${trustedType.simpleName} exposes a constructor that bypasses admission",
            )
        }
    }

    @Test
    fun `trusted evidence collections cannot change after identity creation`() {
        val root = repositoryRoot()
        val compiler = validCompiler().copy(
            languageSettings = linkedMapOf("first" to "1", "second" to "2"),
            compilerOptions = listOf(compilerOption("-first"), compilerOption("-second")),
            resolvedDependencies = listOf(
                resolvedArtifact("dependency:a", contentSha256 = DIGEST_A),
                resolvedArtifact("dependency:b", contentSha256 = DIGEST_B),
            ),
            compilerPlugins = listOf(
                compilerPlugin("plugin:a", artifactSha256 = DIGEST_A),
                compilerPlugin("plugin:b", artifactSha256 = DIGEST_B),
            ),
        )
        val declarations = listOf(
            RawOwnedDeclarationInput("sample.First", "misleading-layout/src/First.kt"),
            RawOwnedDeclarationInput("sample.Second", "misleading-layout/src/Second.kt"),
        )
        val first = validCompilationUnit(compiler = compiler).copy(
            declarations = declarations,
            families = linkedSetOf("sample.First", "sample.Second"),
        )
        val second = validCompilationUnit(
            ownerId = "unit-test",
            sourceSetName = "jvmTest",
            compiler = compiler,
        ).copy(
            declarations = declarations,
            families = first.families,
        )
        val firstSourcePath = root.resolve("src/First.kt")
        val secondSourcePath = root.resolve("src/Second.kt")
        Files.createDirectories(firstSourcePath.parent)
        Files.writeString(firstSourcePath, "class First")
        Files.writeString(secondSourcePath, "class Second")
        val operation = admitted(
            validInput(
                root = root,
                sourceState = validSourceState(
                    inputs = listOf(
                        includedSource(
                            "src/First.kt",
                            RawSourceInputKind.UNTRACKED,
                            sha256(firstSourcePath),
                        ),
                        includedSource(
                            "src/Second.kt",
                            RawSourceInputKind.UNTRACKED,
                            sha256(secondSourcePath),
                        ),
                    ),
                    root = root,
                ),
                buildOwnership = available(first, second),
            ),
        )
        val unit = operation.resolvedScope.compilationUnits.first()
        val configuration = unit.semanticConfiguration
        val mutationAttempts = listOf<() -> Unit>(
            { (operation.repositoryState.compilationUnits as MutableList).clear() },
            { (operation.repositoryState.sourceState.inputs as MutableList).clear() },
            { (operation.resolvedScope.compilationUnits as MutableList).clear() },
            { (unit.sourceRoots as MutableList).clear() },
            { (unit.declarations as MutableList).clear() },
            { (unit.families as MutableSet).clear() },
            { (unit.generatedSourceRoots as MutableList).clear() },
            { (unit.sourceSetRelationships as MutableSet).clear() },
            { (configuration.languageSettings as MutableMap).clear() },
            { (configuration.compilerOptions as MutableList).clear() },
            { (configuration.resolvedDependencies as MutableList).clear() },
            { (configuration.compilerPlugins as MutableList).clear() },
            { (configuration.compilerPlugins.first().classpath as MutableList).clear() },
            { (configuration.compilerPlugins.first().options as MutableList).clear() },
        )

        mutationAttempts.forEach { mutate ->
            assertThrows(UnsupportedOperationException::class.java, mutate)
        }
    }

    @Test
    fun `typed rejection evidence cannot be changed by its caller`() {
        val candidateInput = mutableListOf("unit-main", "unit-other")
        val unitInput = mutableListOf("unit-main", "unit-test")
        val ambiguous = RepositoryOperationRejection.AmbiguousScope(
            selector = RawScopeSelector.Declaration("sample.Application"),
            candidates = candidateInput,
        )
        val incompatible = RepositoryOperationRejection.IncompatibleSemanticConfigurations(unitInput)

        candidateInput.clear()
        unitInput.clear()

        assertEquals(listOf("unit-main", "unit-other"), ambiguous.candidates)
        assertEquals(listOf("unit-main", "unit-test"), incompatible.compilationUnits)
        assertThrows(UnsupportedOperationException::class.java) {
            (ambiguous.candidates as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (incompatible.compilationUnits as MutableList).clear()
        }
    }

    private fun admitted(input: RawRepositoryOperationInput): AdmittedRepositoryOperation =
        assertInstanceOf(
            RepositoryOperationAdmission.Result.Admitted::class.java,
            RepositoryOperationAdmission.admit(input),
        ).operation

    private fun assertRejected(
        expected: RepositoryOperationRejection,
        input: RawRepositoryOperationInput,
    ) = assertRejectedResult(expected, RepositoryOperationAdmission.admit(input))

    private fun assertRejectedResult(
        expected: RepositoryOperationRejection,
        result: RepositoryOperationAdmission.Result,
    ) {
        val rejected = assertInstanceOf(
            RepositoryOperationAdmission.Result.Rejected::class.java,
            result,
        )
        assertEquals(expected, rejected.rejection)
        assertFalse(rejected.rejection.mutationStarted)
    }

    private fun validInput(
        root: Path = repositoryRoot(),
        sourceState: RawSourceStateInput = validSourceState(root = root),
        buildOwnership: RawBuildOwnershipEvidence = available(validCompilationUnit()),
        scope: List<RawScopeSelector> = listOf(RawScopeSelector.Module("application")),
        resourceBounds: RawResourceBoundsInput = validBounds(),
    ): RawRepositoryOperationInput = RawRepositoryOperationInput(
        repository = RawRepositoryInput(
            requestedRoot = root.toString(),
            baseDirectory = root.parent.toString(),
        ),
        sourceState = sourceState,
        buildOwnership = buildOwnership,
        scope = scope,
        resourceBounds = resourceBounds,
    )

    private fun repositoryRoot(name: String = "repository"): Path =
        temporaryDirectory.resolve(name).also { root ->
            if (!Files.exists(root.resolve(".git"))) {
                Files.createDirectories(root.resolve("misleading-layout/src"))
                Files.writeString(
                    root.resolve("misleading-layout/src/Application.kt"),
                    "package sample\nclass Application\n",
                )
                git(root, "init", "--quiet")
                git(root, "config", "user.name", "Kast Admission Test")
                git(root, "config", "user.email", "kast-admission@example.invalid")
                git(root, "add", ".")
                git(root, "commit", "--quiet", "-m", "fixture")
            }
        }

    private fun validSourceState(
        inputs: List<RawSourceInput> = emptyList(),
        root: Path = repositoryRoot(),
    ): RawSourceStateInput = RawSourceStateInput(
        revision = git(root, "rev-parse", "HEAD"),
        inputs = inputs,
    )

    private fun git(
        root: Path,
        vararg arguments: String,
    ): String {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }.trim()
        check(process.waitFor() == 0) {
            "git ${arguments.joinToString(" ")} failed in $root: $output"
        }
        return output
    }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun includedSource(
        path: String,
        kind: RawSourceInputKind,
        digest: String = DIGEST_A,
    ): RawSourceInput = RawSourceInput(
        path = path,
        kind = kind,
        presence = RawSourceInputPresence.PRESENT,
        disposition = RawSourceInputDisposition.INCLUDED,
        contentSha256 = digest,
    )

    private fun available(
        vararg units: RawCompilationUnitInput,
    ): RawBuildOwnershipEvidence.Available = RawBuildOwnershipEvidence.Available(units.toList())

    private fun validCompilationUnit(
        ownerId: String = "unit-main",
        moduleIdentity: String = ":application",
        moduleName: String = "application",
        sourceSetName: String = "jvmMain",
        variantName: String = "debug",
        generatedSourceRoots: Set<String> = setOf("misleading-layout/src/generated"),
        sourceSetRelationships: Set<RawSourceSetRelationshipInput> = emptySet(),
        compiler: RawCompilerInput = validCompiler(),
    ): RawCompilationUnitInput = RawCompilationUnitInput(
        ownerId = ownerId,
        moduleIdentity = moduleIdentity,
        moduleName = moduleName,
        sourceSetName = sourceSetName,
        variantName = variantName,
        sourceRoots = setOf("misleading-layout/src"),
        declarations = listOf(
            RawOwnedDeclarationInput(
                fullyQualifiedName = "sample.Application",
                path = "misleading-layout/src/Application.kt",
            ),
        ),
        families = setOf("sample.Application"),
        generatedSourceRoots = generatedSourceRoots,
        sourceSetRelationships = sourceSetRelationships,
        compiler = compiler,
    )

    private fun validCompiler(): RawCompilerInput = RawCompilerInput(
        compilerVersion = "2.3.0",
        languageVersion = "2.3",
        apiVersion = "2.3",
        languageSettings = mapOf("progressive" to "true"),
        compilerImplementation = resolvedArtifact(
            component = "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.0",
            contentSha256 = DIGEST_A,
        ),
        toolchain = RawCompilerToolchainInput(
            targetPlatform = "jvm",
            version = "21.0.2",
            vendor = "example-vendor",
            implementation = "example-jdk",
            contentSha256 = DIGEST_A,
        ),
        compilerOptions = listOf(compilerOption("-progressive"), compilerOption("-jvm-target=21")),
        resolvedDependencies = listOf(resolvedArtifact()),
        compilerPlugins = listOf(compilerPlugin()),
    )

    private fun compilerOption(token: String): RawCompilerOptionInput = RawCompilerOptionInput(token)

    private fun resolvedArtifact(
        component: String = "org.example:library:1.0",
        selectedVariant: String = "runtimeElements",
        contentKind: ArtifactContentKind = ArtifactContentKind.FILE,
        contentSha256: String? = DIGEST_A,
    ): RawResolvedArtifactInput = RawResolvedArtifactInput(
        componentIdentity = component,
        selectedVariantIdentity = selectedVariant,
        contentKind = contentKind,
        contentSha256 = contentSha256,
    )

    private fun compilerPlugin(
        pluginId: String = "org.example.plugin",
        artifactSha256: String = DIGEST_A,
        options: List<String>? = listOf("enabled=true"),
    ): RawCompilerPluginInput = RawCompilerPluginInput(
        pluginId = pluginId,
        classpath = listOf(
            resolvedArtifact(
                component = "$pluginId:artifact:1.0",
                contentSha256 = artifactSha256,
            ),
        ),
        options = options?.map(::compilerOption),
    )

    private fun sourceSetRelationship(
        kind: SourceSetRelationshipKind,
        targetCompilationUnitId: String,
    ): RawSourceSetRelationshipInput = RawSourceSetRelationshipInput(kind, targetCompilationUnitId)

    private fun validBounds(): RawResourceBoundsInput = RawResourceBoundsInput(
        timeLimitMillis = 30_000,
        memoryLimitBytes = 512L * 1024L * 1024L,
        traversalDepthLimit = 32,
        pathLimit = 10_000,
        resultLimit = 1_000,
    )

    private companion object {
        const val NONEXISTENT_REVISION: String = "ffffffffffffffffffffffffffffffffffffffff"
        const val DIGEST_A: String = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DIGEST_B: String = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val DIGEST_C: String = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
