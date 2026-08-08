package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import io.github.amichne.kast.api.contract.query.AddFilePlanQuery
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.MutationPostconditionAuthority
import io.github.amichne.kast.api.contract.query.MutationPostconditionQuery
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.CreateFileParentPolicy
import io.github.amichne.kast.api.contract.result.MutationPostconditionStatus
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import io.github.amichne.kast.api.protocol.MutationPostconditionFailedException
import io.github.amichne.kast.api.protocol.MutationPostconditionLimitation
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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

@TestApplication
internal class ExactAdditionPlanningTest : ExactAdditionPlanningTestSupport() {
    @Test
    fun `postcondition rejects stale project PSI instead of analyzing a synthetic file`() {
        val failure = assertThrows(MutationPostconditionFailedException::class.java) {
            ApplicationManager.getApplication().runReadAction {
                val stale = KtPsiFactory(project).createFile(
                    "StalePostimage.kt",
                    "package demo\n\nclass Before\n",
                )
                requireExactProjectPostimage(stale, "package demo\n\nclass After\n")
            }
        }

        assertEquals(listOf(MutationPostconditionLimitation.SEMANTIC_SOURCE_UNAVAILABLE), failure.limitations)
    }

    @Test
    @OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
    fun `addition proposal PSI retains admitted copy origin and exact target name`() {
        val copy = ApplicationManager.getApplication().runReadAction<KtFile> {
            copiedAdditionKtFile(
                sampleFile as KtFile,
                "package demo\n\nclass CopyOriginProof\n",
                "CopyOriginProof.kt",
            )
        }

        assertSame(sampleFile, copy.copyOrigin)
        assertEquals("CopyOriginProof.kt", copy.name)
    }

    @Test
    fun `add file postcondition proves explicitly refreshed and indexed project PSI`() =
        kotlinx.coroutines.runBlocking {
            ensureProjectReady()
            val sourceRoot = sourceRoot()
            val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
            val target = sourceRoot.resolve("RefreshedPostimage.kt")
            val content = "package demo\n\nfun caller() = callee()\nfun callee() = 1\n"
            val backend = backend(workspaceRoot, workspaceModelReader = model(workspaceRoot, sourceRoot))
            val plan = backend.planAddFile(
                AddFilePlanQuery(AdditionTargetPath.parse(target.toString()), content),
            )
            assertFalse(Files.exists(target))

            backend.applyEdits(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.CreateFile(
                            filePath = target.toString(),
                            content = content,
                            parentPolicy = CreateFileParentPolicy.REQUIRE_EXISTING_PARENTS,
                        ),
                    ),
                ),
            )
            val refresh = backend.refresh(RefreshQuery(filePaths = listOf(target.toString())))
            assertEquals(SemanticAnalysisOutcome.COMPLETE, refresh.semanticOutcome)
            waitUntilIndexesAreReady(project)
            val beforeVerification = Files.readAllBytes(target)

            val verified = backend.verifyMutationPostcondition(
                MutationPostconditionQuery(
                    MutationPostconditionAuthority.AddFile(plan.proof, plan.postimage),
                ).parsed(),
            )

            assertEquals(MutationPostconditionStatus.VERIFIED, verified.status)
            assertArrayEquals(beforeVerification, Files.readAllBytes(target))
        }

    @Test
    fun `multi-declaration add file excludes proposal-internal sibling references without writing`() =
        kotlinx.coroutines.runBlocking {
            ensureProjectReady()
            val sourceRoot = sourceRoot()
            val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
            val target = sourceRoot.resolve("SiblingReferences.kt")
            val content = "package demo\n\nfun a() = b()\nfun b() = 1\n"
            val backend = backend(workspaceRoot, workspaceModelReader = model(workspaceRoot, sourceRoot))

            val plan = backend.planAddFile(
                AddFilePlanQuery(AdditionTargetPath.parse(target.toString()), content),
            )

            assertFalse(Files.exists(target))
            assertEquals(0, plan.proof.outboundEvidence.cardinality.value)
            assertArrayEquals(content.toByteArray(), plan.postimage.copyBytes())
            assertFalse(Files.exists(target))
        }

    @Test
    fun `addition outbound proof excludes compiler-proven package qualifier segments`() =
        kotlinx.coroutines.runBlocking {
            ensureProjectReady()
            val sourceRoot = sourceRoot()
            val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
            val target = sourceRoot.resolve("QualifiedExternalReference.kt")
            val content =
                "package demo\n\nfun qualifiedExternal(): String = demo.greet(\"friend\")\n"
            val backend = backend(workspaceRoot, workspaceModelReader = model(workspaceRoot, sourceRoot))

            val plan = backend.planAddFile(
                AddFilePlanQuery(AdditionTargetPath.parse(target.toString()), content),
            )

            assertFalse(Files.exists(target))
            assertTrue(plan.proof.outboundEvidence.cardinality.value > 0)
            val occurrenceTexts = plan.proof.outboundEvidence.occurrences.map { occurrence ->
                content.substring(occurrence.range.startOffset.value, occurrence.range.endOffset.value)
            }
            assertTrue("greet" in occurrenceTexts)
            assertFalse("demo" in occurrenceTexts)
            assertFalse(Files.exists(target))
        }

    @Test
    fun `addition finalization rejects new Kotlin and Java source files without writing target`() =
        kotlinx.coroutines.runBlocking {
            ensureProjectReady()
            val sourceRoot = sourceRoot()
            val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)

            listOf("kt", "java").forEach { extension ->
                val target = sourceRoot.resolve("FinalizationTarget${extension.uppercase()}.kt")
                val inserted = sourceRoot.resolve("InsertedDuringFinalization.${extension}")
                val generationReads = AtomicInteger()
                val backend = backend(
                    workspaceRoot = workspaceRoot,
                    psiGeneration = {
                        if (generationReads.incrementAndGet() == 2) {
                            Files.writeString(
                                inserted,
                                if (extension == "kt") {
                                    "package demo\n\nclass InsertedDuringFinalization\n"
                                } else {
                                    "package demo; final class InsertedDuringFinalization {}\n"
                                },
                            )
                        }
                        7L
                    },
                    workspaceModelReader = model(workspaceRoot, sourceRoot),
                )
                try {
                    val failure = assertThrows(AdditionProofIncompleteException::class.java) {
                        kotlinx.coroutines.runBlocking {
                            backend.planAddFile(
                                AddFilePlanQuery(
                                    AdditionTargetPath.parse(target.toString()),
                                    "package demo\n\nclass FinalizationTarget${extension.uppercase()}\n",
                                ),
                            )
                        }
                    }
                    assertLimitation(failure, AdditionProofLimitation.SOURCE_CONTEXT_CHANGED)
                    assertFalse(Files.exists(target))
                } finally {
                    Files.deleteIfExists(inserted)
                }
            }
        }

    @Test
    fun `add file postcondition verifier proves exact addition and rejects changed source context`() =
        kotlinx.coroutines.runBlocking {
            ensureProjectReady()
            val sourceRoot = sourceRoot()
            val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
            val target = sourceRoot.resolve("VerifiedAdditionFile.kt")
            val content = "package demo\n\nclass VerifiedAdditionFile\n"
            val backend = backend(workspaceRoot, workspaceModelReader = model(workspaceRoot, sourceRoot))
            val plan = backend.planAddFile(
                AddFilePlanQuery(AdditionTargetPath.parse(target.toString()), content),
            )
            lateinit var targetVirtualFile: com.intellij.openapi.vfs.VirtualFile
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runWriteAction {
                    targetVirtualFile = sampleFile.virtualFile.parent.createChildData(this, target.fileName.toString())
                    VfsUtil.saveText(targetVirtualFile, content)
                }
            }
            waitUntilIndexesAreReady(project)
            val bytesBeforeVerification = Files.readAllBytes(target)

            val verified = backend.verifyMutationPostcondition(
                MutationPostconditionQuery(
                    MutationPostconditionAuthority.AddFile(plan.proof, plan.postimage),
                ).parsed(),
            )

            assertEquals(MutationPostconditionStatus.VERIFIED, verified.status)
            assertArrayEquals(bytesBeforeVerification, Files.readAllBytes(target))

            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runWriteAction {
                    VfsUtil.saveText(sampleFile.virtualFile, sampleFile.text + "\n// context drift")
                }
            }
            val contextFailure = runCatching {
                backend.verifyMutationPostcondition(
                    MutationPostconditionQuery(
                        MutationPostconditionAuthority.AddFile(plan.proof, plan.postimage),
                    ).parsed(),
                )
            }.exceptionOrNull() as? MutationPostconditionFailedException
                ?: error("Expected changed add-file source context to fail")
            assertEquals(listOf(MutationPostconditionLimitation.SOURCE_CONTEXT_CHANGED), contextFailure.limitations)
        }

    @Test
    fun `add file postcondition maps an unreadable proof image to source context changed`() =
        kotlinx.coroutines.runBlocking {
            ensureProjectReady()
            val sourceRoot = sourceRoot()
            assumeTrue(Files.getFileStore(sourceRoot).supportsFileAttributeView("posix"))
            val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
            val contextPath = sourceRoot.resolve("AUnreadablePostconditionContext.kt")
            val target = sourceRoot.resolve("UnreadablePostconditionTarget.kt")
            val content = "package demo\n\nclass UnreadablePostconditionTarget\n"
            lateinit var contextVirtualFile: com.intellij.openapi.vfs.VirtualFile
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runWriteAction {
                    contextVirtualFile = sampleFile.virtualFile.parent.createChildData(
                        this,
                        contextPath.fileName.toString(),
                    )
                    VfsUtil.saveText(contextVirtualFile, "package demo\n\nclass AUnreadablePostconditionContext\n")
                }
            }
            waitUntilIndexesAreReady(project)
            val backend = backend(workspaceRoot, workspaceModelReader = model(workspaceRoot, sourceRoot))
            val plan = backend.planAddFile(
                AddFilePlanQuery(AdditionTargetPath.parse(target.toString()), content),
            )
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runWriteAction {
                    val targetVirtualFile = sampleFile.virtualFile.parent.createChildData(this, target.fileName.toString())
                    VfsUtil.saveText(targetVirtualFile, content)
                }
            }
            waitUntilIndexesAreReady(project)
            val originalPermissions = Files.getPosixFilePermissions(contextPath)
            try {
                Files.setPosixFilePermissions(contextPath, emptySet())

                val failure = assertThrows(MutationPostconditionFailedException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        backend.verifyMutationPostcondition(
                            MutationPostconditionQuery(
                                MutationPostconditionAuthority.AddFile(plan.proof, plan.postimage),
                            ).parsed(),
                        )
                    }
                }

                assertEquals(
                    listOf(MutationPostconditionLimitation.SOURCE_CONTEXT_CHANGED),
                    failure.limitations,
                )
            } finally {
                Files.setPosixFilePermissions(contextPath, originalPermissions)
            }
        }

    @Test
    fun `FILE_BOTTOM structural proof rejects an extra appended declaration`() {
        val preimage = "package demo\n\nclass Existing\n"
        val separator = "\n"
        val authorized = "class AuthorizedOnlyDeclaration\n"

        val failure = assertThrows(MutationPostconditionFailedException::class.java) {
            ApplicationManager.getApplication().runReadAction {
                val file = KtPsiFactory(project).createFile(
                    "ExtraFileBottomDeclaration.kt",
                    preimage + separator + authorized + "\nclass Extra\n",
                )
                exactFileBottomDeclaration(
                    file,
                    provenFileBottomOffset = preimage.length,
                    relativeBaseOffset = preimage.length + separator.length,
                )
            }
        }

        assertEquals(listOf(MutationPostconditionLimitation.DECLARATION_SET_MISMATCH), failure.limitations)
    }

}
