package io.github.amichne.kast.idea

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import io.github.amichne.kast.api.contract.query.AddDeclarationPlanQuery
import io.github.amichne.kast.api.contract.query.AddFilePlanQuery
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.MutationPostconditionAuthority
import io.github.amichne.kast.api.contract.query.MutationPostconditionQuery
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.CreateFileParentPolicy
import io.github.amichne.kast.api.contract.result.MutationPostconditionStatus
import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTargetPreimageSha256
import io.github.amichne.kast.api.contract.result.AdditionPostimageSha256
import io.github.amichne.kast.api.contract.result.ExactAddDeclarationProof
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import io.github.amichne.kast.api.protocol.MutationPostconditionFailedException
import io.github.amichne.kast.api.protocol.MutationPostconditionLimitation
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.mutation.copiedAdditionKtFile
import io.github.amichne.kast.idea.backend.mutation.exactFileBottomDeclaration
import io.github.amichne.kast.idea.backend.mutation.requireExactProjectPostimage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.analysis.api.projectStructure.copyOrigin
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
internal class ExactAdditionPlannerContractTest : ExactAdditionPlanningTestSupport() {
    @Test
    fun `add file planning proves an exact absent image and does not write`() = kotlinx.coroutines.runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("PlannerUniqueAdded.kt")
        val content = "package demo\n\nclass PlannerUniqueAdded\n"

        val result = backend(workspaceRoot, workspaceModelReader = model(workspaceRoot, sourceRoot))
            .planAddFile(AddFilePlanQuery(AdditionTargetPath.parse(target.toString()), content))

        assertFalse(Files.exists(target))
        assertEquals(target.toString(), result.proof.targetPath.value)
        assertEquals("PlannerUniqueAdded", result.proof.declarations.single().name)
        assertArrayEquals(content.toByteArray(), result.postimage.copyBytes())
    }

    @Test
    fun `add declaration planning appends at compiler file bottom without writing`() = kotlinx.coroutines.runBlocking {
        ensureProjectReady()
        val target = Path.of(sampleFile.virtualFile.path).toAbsolutePath().normalize()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), target.toString())
        val before = Files.readAllBytes(target)

        val result = backend(workspaceRoot, workspaceModelReader = model(workspaceRoot, sourceRoot))
            .planAddDeclaration(
                AddDeclarationPlanQuery(
                    targetPath = AdditionTargetPath.parse(target.toString()),
                    expectedCurrentSha256 = AdditionTargetPreimageSha256.of(FileHashing.sha256(before)),
                    proposedDeclaration = "class PlannerUniqueDeclaration",
                ),
            )

        assertArrayEquals(before, Files.readAllBytes(target))
        assertEquals(readAction { sampleFile.textLength }, result.proof.insertion.offset.value)
        assertArrayEquals(before, result.image.preimage.copyBytes())
    }

    @Test
    fun `addition rejects existing target missing parent and indexed rebinding candidate`() = kotlinx.coroutines.runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val backend = backend(workspaceRoot, workspaceModelReader = model(workspaceRoot, sourceRoot))

        assertLimitation(
            assertThrows(AdditionProofIncompleteException::class.java) {
                kotlinx.coroutines.runBlocking {
                    backend.planAddFile(
                        AddFilePlanQuery(
                            AdditionTargetPath.parse(Path.of(sampleFile.virtualFile.path).toString()),
                            "package demo\n\nclass ExistingTarget",
                        ),
                    )
                }
            },
            AdditionProofLimitation.TARGET_ALREADY_EXISTS,
        )
        assertLimitation(
            assertThrows(AdditionProofIncompleteException::class.java) {
                kotlinx.coroutines.runBlocking {
                    backend.planAddFile(
                        AddFilePlanQuery(
                            AdditionTargetPath.parse(sourceRoot.resolve("missing/Added.kt").toString()),
                            "package demo\n\nclass MissingParent",
                        ),
                    )
                }
            },
            AdditionProofLimitation.TARGET_PARENT_MISSING,
        )
        assertLimitation(
            assertThrows(AdditionProofIncompleteException::class.java) {
                kotlinx.coroutines.runBlocking {
                    backend.planAddFile(
                        AddFilePlanQuery(
                            AdditionTargetPath.parse(sourceRoot.resolve("GreetCollision.kt").toString()),
                            "package demo\n\nclass greet",
                        ),
                    )
                }
            },
            AdditionProofLimitation.DECLARATION_COLLISION,
        )
    }

    @Test
    fun `addition rejects implicit convention and return-type-only compiler collision`() = kotlinx.coroutines.runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val backend = backend(workspaceRoot, workspaceModelReader = model(workspaceRoot, sourceRoot))

        assertLimitation(
            additionFailure(backend, sourceRoot, "operator fun plus(other: Int): Int = other"),
            AdditionProofLimitation.IMPLICIT_LOOKUP_UNACCOUNTED,
        )
        assertLimitation(
            additionFailure(
                backend,
                sourceRoot,
                "fun plannerReturnOnly(value: Int): String = \"x\"\nfun plannerReturnOnly(value: Int): Int = value",
            ),
            AdditionProofLimitation.DECLARATION_COLLISION,
        )
        assertLimitation(
            additionFailure(
                backend,
                sourceRoot,
                "class PlannerClassifierCollision\ntypealias PlannerClassifierCollision = String",
            ),
            AdditionProofLimitation.DECLARATION_COLLISION,
        )
    }

    @Test
    fun `addition planning rejects every unmodeled implicit-call PSI form`() = kotlinx.coroutines.runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val backend = backend(workspaceRoot, workspaceModelReader = model(workspaceRoot, sourceRoot))
        val declarations = listOf(
            "fun implicitFor(value: String): String { for (item in value) { item.code }; return value }",
            "fun implicitArray(value: String): String { val values = arrayOf(value); values[0] = values[0]; return values[0] }",
            "fun implicitDestructuring(value: String): String { val (first, second) = Pair(value, value); return first + second }",
            "val implicitDelegate: Int by lazy { 1 }",
        )

        declarations.forEach { declaration ->
            assertLimitation(
                additionFailure(backend, sourceRoot, declaration),
                AdditionProofLimitation.IMPLICIT_LOOKUP_UNACCOUNTED,
            )
        }
    }

    @Test
    fun `addition collision proof includes dependency classifiers and callable signatures`() =
        kotlinx.coroutines.runBlocking {
            ensureProjectReady()
            dependencyCollisionFileFixture.get()
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runWriteAction {
                    ModuleRootModificationUtil.addDependency(
                        mainModuleFixture.get(),
                        secondaryModuleFixture.get(),
                        DependencyScope.COMPILE,
                        false,
                        true,
                    )
                }
            }
            waitUntilIndexesAreReady(project)
            val sourceRoot = sourceRoot()
            val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
            val backend = backend(workspaceRoot, workspaceModelReader = model(workspaceRoot, sourceRoot))

            assertLimitation(
                additionFailure(
                    backend,
                    sourceRoot,
                    "class DependencyCollision",
                ),
                AdditionProofLimitation.DECLARATION_COLLISION,
            )
            assertLimitation(
                additionFailure(
                    backend,
                    sourceRoot,
                    "fun dependencyCollision(value: String): Int = value.length",
                ),
                AdditionProofLimitation.DECLARATION_COLLISION,
            )
        }

    @Test
    fun `add file rejects a symbolic-link parent escape without creating the target`() = kotlinx.coroutines.runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val outside = Files.createTempDirectory("kast-addition-outside")
        val link = sourceRoot.resolve("planner-link-${System.nanoTime()}")
        try {
            Files.createSymbolicLink(link, outside)
            val target = link.resolve("Escaped.kt")
            val failure = assertThrows(AdditionProofIncompleteException::class.java) {
                kotlinx.coroutines.runBlocking {
                    backend(workspaceRoot, workspaceModelReader = model(workspaceRoot, sourceRoot)).planAddFile(
                        AddFilePlanQuery(
                            AdditionTargetPath.parse(target.toString()),
                            "package demo\n\nclass Escaped",
                        ),
                    )
                }
            }
            assertLimitation(failure, AdditionProofLimitation.TARGET_PARENT_MISSING)
            assertFalse(Files.exists(outside.resolve("Escaped.kt")))
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `addition rejects a symbolic-link source-context file`() = kotlinx.coroutines.runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val outside = Files.createTempFile("kast-addition-context", ".kt")
        val link = sourceRoot.resolve("PlannerLinkedContext${System.nanoTime()}.kt")
        try {
            Files.createSymbolicLink(link, outside)
            val failure = additionFailure(
                backend(workspaceRoot, workspaceModelReader = model(workspaceRoot, sourceRoot)),
                sourceRoot,
                "class PlannerLinkedContextTarget",
            )
            assertLimitation(failure, AdditionProofLimitation.SOURCE_CONTEXT_CHANGED)
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `addition selects the most-specific nested owner and rejects equal-specificity ambiguity`() =
        kotlinx.coroutines.runBlocking {
            ensureProjectReady()
            val broadRoot = sourceRoot()
            val workspaceRoot = commonWorkspaceRoot(broadRoot.toString(), sampleFile.virtualFile.path)
            val nestedRoot = createNestedSourceRoot(broadRoot)
            try {
                val broad = association("main", workspaceRoot, ":", "main", broadRoot)
                val nested = association("nested", workspaceRoot, ":nested", "main", nestedRoot)
                val target = nestedRoot.resolve("PlannerNestedAdded.kt")
                val result = backend(
                    workspaceRoot,
                    workspaceModelReader = model(workspaceRoot, listOf(broad, nested)),
                ).planAddFile(
                    AddFilePlanQuery(
                        AdditionTargetPath.parse(target.toString()),
                        "package demo.nested\n\nclass PlannerNestedAdded",
                    ),
                )
                assertEquals(nestedRoot.toString(), result.proof.owner.sourceRoot.value)
                assertEquals("nested", result.proof.owner.ideaModuleName.value)

                val ambiguous = association("duplicate", workspaceRoot, ":duplicate", "main", nestedRoot)
                val failure = try {
                    backend(
                        workspaceRoot,
                        workspaceModelReader = model(workspaceRoot, listOf(broad, nested, ambiguous)),
                    ).planAddFile(
                        AddFilePlanQuery(
                            AdditionTargetPath.parse(nestedRoot.resolve("PlannerAmbiguous.kt").toString()),
                            "package demo.nested\n\nclass PlannerAmbiguous",
                        ),
                    )
                    error("Expected ambiguous addition owner to fail")
                } catch (failure: AdditionProofIncompleteException) {
                    failure
                }
                assertLimitation(failure, AdditionProofLimitation.SOURCE_OWNER_AMBIGUOUS)
            } finally {
                deleteNestedSourceRoot(nestedRoot)
            }
        }

    @Test
    fun `project-model fingerprint is stable across association iteration order`() = kotlinx.coroutines.runBlocking {
        ensureProjectReady()
        val mainRoot = sourceRoot()
        val secondaryRoot = Path.of(secondarySourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val workspaceRoot = commonWorkspaceRoot(mainRoot.toString(), secondaryRoot.toString())
        val main = association("main", workspaceRoot, ":", "main", mainRoot)
        val secondary = association("secondary", workspaceRoot, ":secondary", "test", secondaryRoot)
        val target = mainRoot.resolve("PlannerOrderedModel.kt")
        val query = AddFilePlanQuery(
            AdditionTargetPath.parse(target.toString()),
            "package demo\n\nclass PlannerOrderedModel",
        )

        val forward = backend(
            workspaceRoot,
            workspaceModelReader = model(workspaceRoot, listOf(main, secondary)),
        ).planAddFile(query)
        val reversed = backend(
            workspaceRoot,
            workspaceModelReader = model(workspaceRoot, listOf(secondary, main)),
        ).planAddFile(query)

        assertEquals(forward.proof.context.projectModelFingerprint, reversed.proof.context.projectModelFingerprint)
        assertEquals(forward.proof.context.contextFileHashes, reversed.proof.context.contextFileHashes)
    }
}
