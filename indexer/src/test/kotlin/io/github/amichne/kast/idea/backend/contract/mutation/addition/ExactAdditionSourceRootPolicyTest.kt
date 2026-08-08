package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.query.AddDeclarationPlanQuery
import io.github.amichne.kast.api.contract.query.AddFilePlanQuery
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTargetPreimageSha256
import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import io.github.amichne.kast.api.validation.FileHashing
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

@TestApplication
internal class ExactAdditionSourceRootPolicyTest : ExactAdditionPlanningTestSupport() {
    @Test
    fun `duplicate observations of one source set remain one exact owner`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = requireNotNull(sourceRoot.parent)
        val ideaModuleName = ApplicationManager.getApplication().runReadAction<String> {
            requireNotNull(
                ProjectFileIndex.getInstance(project).getModuleForFile(sampleFile.virtualFile),
            ).name
        }
        val exactObservation = association(ideaModuleName, workspaceRoot, ":", "main", sourceRoot)
        val aggregateObservation = association(
            "$ideaModuleName.aggregate",
            workspaceRoot,
            ":",
            "main",
            sourceRoot,
        )
        val target = sourceRoot.resolve("DuplicateOwnerObservation.kt")
        val query = AddFilePlanQuery(
            targetPath = AdditionTargetPath.parse(target.toString()),
            proposedContent = "package demo\n\nclass DuplicateOwnerObservation\n",
        )

        val forward = backend(
            workspaceRoot,
            workspaceModelReader = model(workspaceRoot, listOf(aggregateObservation, exactObservation)),
        ).planAddFile(query)
        val reversed = backend(
            workspaceRoot,
            workspaceModelReader = model(workspaceRoot, listOf(exactObservation, aggregateObservation)),
        ).planAddFile(query)

        assertEquals(ideaModuleName, forward.proof.owner.ideaModuleName.value)
        assertEquals(forward.proof.owner, reversed.proof.owner)
    }

    @Test
    fun `duplicate observations without the indexed module remain unproven`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = requireNotNull(sourceRoot.parent)
        val ideaModuleName = ApplicationManager.getApplication().runReadAction<String> {
            requireNotNull(
                ProjectFileIndex.getInstance(project).getModuleForFile(sampleFile.virtualFile),
            ).name
        }
        val target = sourceRoot.resolve("UnprovenDuplicateOwnerObservation.kt")

        val failure = assertThrows(AdditionProofIncompleteException::class.java) {
            runBlocking {
                backend(
                    workspaceRoot,
                    workspaceModelReader = model(
                        workspaceRoot,
                        listOf(
                            association("$ideaModuleName.aggregate", workspaceRoot, ":", "main", sourceRoot),
                            association("$ideaModuleName.other", workspaceRoot, ":", "main", sourceRoot),
                        ),
                    ),
                ).planAddFile(
                    AddFilePlanQuery(
                        targetPath = AdditionTargetPath.parse(target.toString()),
                        proposedContent = "package demo\n\nclass UnprovenDuplicateOwnerObservation\n",
                    ),
                )
            }
        }

        assertLimitation(failure, AdditionProofLimitation.SOURCE_OWNER_UNPROVEN)
        assertFalse(Files.exists(target))
    }

    @Test
    fun `add declaration rejects every hard-excluded model-owned source root`() = runBlocking {
        ensureProjectReady()
        val workspaceRoot = sourceRoot()
        HARD_EXCLUDED_NAMES.forEachIndexed { index, excludedName ->
            val excludedRoot = createSourceRoot(
                relativeRoot = "$excludedName/generated/planner-$index",
                fileName = "Excluded$index.kt",
                content = "package demo.excluded$index\n\nclass Excluded$index\n",
            )
            val target = excludedRoot.resolve("Excluded$index.kt")
            try {
                val before = Files.readAllBytes(target)
                val failure = assertThrows(AdditionProofIncompleteException::class.java) {
                    runBlocking {
                        backend(
                            workspaceRoot,
                            workspaceModelReader = model(workspaceRoot, excludedRoot),
                        ).planAddDeclaration(
                            AddDeclarationPlanQuery(
                                targetPath = AdditionTargetPath.parse(target.toString()),
                                expectedCurrentSha256 = AdditionTargetPreimageSha256.of(FileHashing.sha256(before)),
                                proposedDeclaration = "class Rejected$index",
                            ),
                        )
                    }
                }
                assertLimitation(failure, AdditionProofLimitation.HARD_EXCLUDED_MUTATION_TARGET)
            } finally {
                deleteSourceRoot(workspaceRoot.resolve(excludedName))
            }
        }
    }

    @Test
    fun `add declaration rejects a generated source root outside hard exclusions`() = runBlocking {
        ensureProjectReady()
        val workspaceRoot = sourceRoot()
        val generatedRoot = createSourceRoot(
            relativeRoot = "generated/kotlin/planner-valid",
            fileName = "Generated.kt",
            content = "package demo.generated\n\nclass Generated\n",
        )
        val target = generatedRoot.resolve("Generated.kt")
        try {
            val before = Files.readAllBytes(target)

            val failure = assertThrows(AdditionProofIncompleteException::class.java) {
                runBlocking {
                    backend(
                        workspaceRoot,
                        workspaceModelReader = model(
                            workspaceRoot,
                            listOf(
                                association(
                                    "generated",
                                    workspaceRoot,
                                    ":generated",
                                    "main",
                                    generatedGradleSourceRoot(generatedRoot),
                                ),
                            ),
                        ),
                    ).planAddDeclaration(
                        AddDeclarationPlanQuery(
                            targetPath = AdditionTargetPath.parse(target.toString()),
                            expectedCurrentSha256 = AdditionTargetPreimageSha256.of(FileHashing.sha256(before)),
                            proposedDeclaration = "class RejectedGenerated",
                        ),
                    )
                }
            }

            assertLimitation(failure, AdditionProofLimitation.GENERATED_SOURCE_READ_ONLY)
        } finally {
            deleteSourceRoot(workspaceRoot.resolve("generated"))
        }
    }

    @Test
    fun `allowed addition ignores mutation authority of a second generated model source root`() = runBlocking {
        ensureProjectReady()
        val allowedRoot = sourceRoot()
        val workspaceRoot = requireNotNull(allowedRoot.parent)
        val excludedRoot = createSourceRoot(
            relativeRoot = "build/generated/secondary-proof-root",
            fileName = "ExcludedContext.kt",
            content = "package demo.excluded.context\n\nclass ExcludedContext\n",
        )
        val target = Path.of(sampleFile.virtualFile.path).toAbsolutePath().normalize()
        try {
            val result = backend(
                workspaceRoot,
                workspaceModelReader = model(
                    workspaceRoot,
                    listOf(
                        association("main", workspaceRoot, ":", "main", allowedRoot),
                        association(
                            "excluded",
                            workspaceRoot,
                            ":excluded",
                            "main",
                            generatedGradleSourceRoot(excludedRoot),
                        ),
                    ),
                ),
            ).planAddDeclaration(
                AddDeclarationPlanQuery(
                    targetPath = AdditionTargetPath.parse(target.toString()),
                    expectedCurrentSha256 = AdditionTargetPreimageSha256.of(
                        FileHashing.sha256(Files.readAllBytes(target)),
                    ),
                    proposedDeclaration = "class AllowedWithGeneratedContext",
                ),
            )

            assertEquals(allowedRoot.toString(), result.proof.owner.sourceRoot.value)
        } finally {
            deleteSourceRoot(allowedRoot.resolve("build"))
        }
    }

    @Test
    fun `allowed addition ignores mutation authority of a second outside-workspace model source root`() = runBlocking {
        ensureProjectReady()
        val allowedRoot = sourceRoot().toRealPath()
        val workspaceRoot = requireNotNull(allowedRoot.parent).toRealPath()
        val outsideRoot = Files.createTempDirectory(
            requireNotNull(workspaceRoot.parent),
            "kast-outside-addition-proof-root",
        ).toRealPath()
        val outsideFile = outsideRoot.resolve("OutsideContext.kt")
        try {
            assertFalse(outsideRoot.startsWith(workspaceRoot))
            Files.writeString(outsideFile, "package outside\n\nclass OutsideContext\n")
            val target = Path.of(sampleFile.virtualFile.path).toAbsolutePath().normalize()

            val result = backend(
                workspaceRoot,
                workspaceModelReader = model(
                    workspaceRoot,
                    listOf(
                        association("main", workspaceRoot, ":", "main", allowedRoot),
                        association("outside", workspaceRoot, ":outside", "main", outsideRoot),
                    ),
                ),
            ).planAddDeclaration(
                AddDeclarationPlanQuery(
                    targetPath = AdditionTargetPath.parse(target.toString()),
                    expectedCurrentSha256 = AdditionTargetPreimageSha256.of(
                        FileHashing.sha256(Files.readAllBytes(target)),
                    ),
                    proposedDeclaration = "class AllowedWithOutsideContext",
                ),
            )

            assertEquals(allowedRoot.toString(), result.proof.owner.sourceRoot.value)
        } finally {
            Files.deleteIfExists(outsideFile)
            Files.deleteIfExists(outsideRoot)
        }
    }

    @Test
    fun `add file rejects unknown target provenance`() = runBlocking {
        ensureProjectReady()
        val workspaceRoot = sourceRoot()
        val unknownRoot = createSourceRoot("unclassified/kotlin", "Anchor.kt", "package unknown\n\nclass Anchor\n")
        val target = unknownRoot.resolve("RejectedUnknown.kt")
        try {
            val failure = assertThrows(AdditionProofIncompleteException::class.java) {
                runBlocking {
                    backend(
                        workspaceRoot,
                        workspaceModelReader = model(
                            workspaceRoot,
                            listOf(association("unknown", workspaceRoot, ":unknown", "main", unknownGradleSourceRoot(unknownRoot))),
                        ),
                    ).planAddFile(
                        AddFilePlanQuery(
                            AdditionTargetPath.parse(target.toString()),
                            "package unknown\n\nclass RejectedUnknown\n",
                        ),
                    )
                }
            }
            assertLimitation(failure, AdditionProofLimitation.SOURCE_PROVENANCE_UNKNOWN)
        } finally {
            deleteSourceRoot(workspaceRoot.resolve("unclassified"))
        }
    }

    @Test
    fun `add file rejects an exact owner outside workspace authority`() = runBlocking {
        ensureProjectReady()
        val workspaceRoot = sourceRoot().toRealPath()
        val outsideBuildRoot = Files.createTempDirectory(
            requireNotNull(workspaceRoot.parent),
            "kast-outside-addition-target",
        ).toRealPath()
        val outsideSourceRoot = Files.createDirectories(outsideBuildRoot.resolve("src/main/kotlin")).toRealPath()
        try {
            val target = outsideSourceRoot.resolve("RejectedOutside.kt")
            val failure = assertThrows(AdditionProofIncompleteException::class.java) {
                runBlocking {
                    backend(
                        workspaceRoot,
                        workspaceModelReader = model(
                            workspaceRoot,
                            listOf(association("outside", outsideBuildRoot, ":", "main", outsideSourceRoot)),
                        ),
                    ).planAddFile(
                        AddFilePlanQuery(
                            AdditionTargetPath.parse(target.toString()),
                            "class RejectedOutside\n",
                        ),
                    )
                }
            }
            assertLimitation(failure, AdditionProofLimitation.OUTSIDE_WORKSPACE_AUTHORITY)
        } finally {
            listOf(outsideSourceRoot, outsideSourceRoot.parent, outsideSourceRoot.parent.parent, outsideBuildRoot)
                .forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `model-authored buildSrc and build-logic roots remain editable`() = runBlocking {
        ensureProjectReady()
        val workspaceRoot = sourceRoot()
        listOf("buildSrc", "build-logic").forEachIndexed { index, rootName ->
            val authoredRoot = createSourceRoot(
                "$rootName/src/main/kotlin",
                "Authored$index.kt",
                "package authored$index\n\nclass Authored$index\n",
            )
            val target = authoredRoot.resolve("Authored$index.kt")
            try {
                val result = backend(
                    workspaceRoot,
                    workspaceModelReader = model(workspaceRoot, authoredRoot),
                ).planAddDeclaration(
                    AddDeclarationPlanQuery(
                        AdditionTargetPath.parse(target.toString()),
                        AdditionTargetPreimageSha256.of(FileHashing.sha256(Files.readAllBytes(target))),
                        "class AddedToAuthored$index",
                    ),
                )
                assertEquals(authoredRoot.toString(), result.proof.owner.sourceRoot.value)
            } finally {
                deleteSourceRoot(workspaceRoot.resolve(rootName))
            }
        }
    }

    private fun createSourceRoot(relativeRoot: String, fileName: String, content: String): Path {
        lateinit var createdRoot: Path
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                val directory = VfsUtil.createDirectoryIfMissing(sourceRoot().resolve(relativeRoot).toString())
                    ?: error("Could not create policy source root")
                val file = directory.findChild(fileName) ?: directory.createChildData(this, fileName)
                VfsUtil.saveText(file, content)
                createdRoot = Path.of(directory.path).toAbsolutePath().normalize()
            }
        }
        waitUntilIndexesAreReady(project)
        return createdRoot
    }

    private fun deleteSourceRoot(root: Path) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                LocalFileSystem.getInstance().findFileByNioFile(root)?.delete(this)
            }
        }
        waitUntilIndexesAreReady(project)
    }

    private companion object {
        val HARD_EXCLUDED_NAMES = listOf("build", ".gradle", ".idea", ".kotlin", "out")
    }
}
