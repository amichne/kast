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
internal class ExactAdditionPlanningTest : KastIndexerBackendContractTestFixture() {
    private val dependencyCollisionFileFixture: TestFixture<PsiFile> =
        secondarySourceRootFixture.psiFileFixture(
            "AdditionDependencyCollisions.kt",
            """
                package demo

                class DependencyCollision

                fun dependencyCollision(value: String): String = value
            """.trimIndent(),
        )
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
    fun `add declaration postcondition verifier rejects changed declaration identity and context`() =
        kotlinx.coroutines.runBlocking {
            ensureProjectReady()
            val target = Path.of(sampleFile.virtualFile.path).toAbsolutePath().normalize()
            val sourceRoot = sourceRoot()
            val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), target.toString())
            val backend = backend(workspaceRoot, workspaceModelReader = model(workspaceRoot, sourceRoot))
            val before = Files.readAllBytes(target)
            val authorizedName = "AuthorizedDeclaration"
            val driftedName = "DriftedDeclaration   "
            val plan = backend.planAddDeclaration(
                AddDeclarationPlanQuery(
                    targetPath = AdditionTargetPath.parse(target.toString()),
                    expectedCurrentSha256 = AdditionTargetPreimageSha256.of(FileHashing.sha256(before)),
                    proposedDeclaration = "class $authorizedName",
                ),
            )
            val driftText = plan.proposedContent.replace("class $authorizedName", "class $driftedName")
            val driftImage = ExactFileImage.of(target.toString(), before, driftText.toByteArray())
            val driftProof = ExactAddDeclarationProof.of(
                targetPath = plan.proof.targetPath,
                targetPreimageSha256 = plan.proof.targetPreimageSha256,
                owner = plan.proof.owner,
                packageIdentity = plan.proof.packageIdentity,
                declaration = plan.proof.declaration,
                insertion = plan.proof.insertion,
                newlinePolicy = plan.proof.newlinePolicy,
                context = plan.proof.context,
                collisionEvidence = plan.proof.collisionEvidence,
                outboundEvidence = plan.proof.outboundEvidence,
                rebindingBaseline = plan.proof.rebindingBaseline,
                postimageSha256 = AdditionPostimageSha256.of(driftImage.postimage.sha256.value),
            )
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runWriteAction {
                    VfsUtil.saveText(sampleFile.virtualFile, driftText)
                }
            }
            waitUntilIndexesAreReady(project)

            val declarationFailure = runCatching {
                backend.verifyMutationPostcondition(
                    MutationPostconditionQuery(
                        MutationPostconditionAuthority.AddDeclaration(driftProof, driftImage),
                    ).parsed(),
                )
            }.exceptionOrNull() as? MutationPostconditionFailedException
                ?: error("Expected changed add-declaration identity to fail")
            assertEquals(
                listOf(MutationPostconditionLimitation.DECLARATION_SET_MISMATCH),
                declarationFailure.limitations,
            )
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

    @Test
    fun `add declaration postcondition verifier reproves exact persisted authority without writing`() =
        kotlinx.coroutines.runBlocking {
            ensureProjectReady()
            val target = Path.of(sampleFile.virtualFile.path).toAbsolutePath().normalize()
            val sourceRoot = sourceRoot()
            val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), target.toString())
            val backend = backend(workspaceRoot, workspaceModelReader = model(workspaceRoot, sourceRoot))
            val before = Files.readAllBytes(target)
            val plan = backend.planAddDeclaration(
                AddDeclarationPlanQuery(
                    targetPath = AdditionTargetPath.parse(target.toString()),
                    expectedCurrentSha256 = AdditionTargetPreimageSha256.of(FileHashing.sha256(before)),
                    proposedDeclaration = "class VerifiedPostconditionDeclaration",
                ),
            )
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runWriteAction {
                    VfsUtil.saveText(sampleFile.virtualFile, plan.proposedContent)
                }
            }
            waitUntilIndexesAreReady(project)
            val postimageBeforeVerification = Files.readAllBytes(target)

            val result = backend.verifyMutationPostcondition(
                MutationPostconditionQuery(
                    MutationPostconditionAuthority.AddDeclaration(plan.proof, plan.image),
                ).parsed(),
            )

            assertEquals(MutationPostconditionStatus.VERIFIED, result.status)
            assertArrayEquals(plan.image.postimage.copyBytes(), postimageBeforeVerification)
            assertArrayEquals(postimageBeforeVerification, Files.readAllBytes(target))
        }

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

    private suspend fun additionFailure(
        backend: io.github.amichne.kast.idea.backend.KastIndexerBackend,
        sourceRoot: Path,
        declaration: String,
    ): AdditionProofIncompleteException = try {
        backend.planAddFile(
            AddFilePlanQuery(
                AdditionTargetPath.parse(sourceRoot.resolve("PlannerFailure${System.nanoTime()}.kt").toString()),
                "package demo\n\n$declaration",
            ),
        )
        error("Expected addition planning to fail")
    } catch (failure: AdditionProofIncompleteException) {
        failure
    }

    private fun sourceRoot(): Path = Path.of(sampleFile.virtualFile.parent.path).toAbsolutePath().normalize()

    private fun model(
        workspaceRoot: Path,
        sourceRoot: Path,
    ): () -> IdeaGradleProjectLoadBridge.GradleWorkspaceModel = model(
        workspaceRoot,
        listOf(association("main", workspaceRoot, ":", "main", sourceRoot)),
    )

    private fun model(
        workspaceRoot: Path,
        associations: List<IdeaGradleProjectLoadBridge.GradleModuleAssociation>,
    ): () -> IdeaGradleProjectLoadBridge.GradleWorkspaceModel = {
        val identities = associations.map { association ->
            IdeaGradleProjectLoadBridge.GradleModuleIdentity(workspaceRoot, association.gradleProjectPath())
        }
        IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
            listOf(workspaceRoot),
            true,
            identities,
            associations.zip(identities) { association, identity ->
                IdeaGradleProjectLoadBridge.LoadedGradleModule(association.ideaModuleName(), identity)
            },
            associations.flatMap { association -> association.sourceSets().flatMap { it.sourceRoots() } }.distinct(),
            associations,
        )
    }

    private fun association(
        moduleName: String,
        workspaceRoot: Path,
        projectPath: String,
        sourceSet: String,
        sourceRoot: Path,
    ): IdeaGradleProjectLoadBridge.GradleModuleAssociation =
        IdeaGradleProjectLoadBridge.GradleModuleAssociation(
            moduleName,
            workspaceRoot,
            workspaceRoot,
            projectPath,
            projectPath == ":",
            false,
            listOf(IdeaGradleProjectLoadBridge.GradleSourceSetAssociation(sourceSet, listOf(sourceRoot))),
        )

    private fun createNestedSourceRoot(broadRoot: Path): Path {
        lateinit var nested: Path
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                val directory = VfsUtil.createDirectoryIfMissing(broadRoot.resolve("planner-nested").toString())
                    ?: error("Could not create nested source root")
                val anchor = directory.findChild("NestedAnchor.kt") ?: directory.createChildData(this, "NestedAnchor.kt")
                VfsUtil.saveText(anchor, "package demo.nested\n\nclass NestedAnchor")
                nested = Path.of(directory.path).toAbsolutePath().normalize()
            }
        }
        waitUntilIndexesAreReady(project)
        return nested
    }

    private fun deleteNestedSourceRoot(nestedRoot: Path) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .findFileByNioFile(nestedRoot)
                    ?.delete(this)
            }
        }
        waitUntilIndexesAreReady(project)
    }

    private fun assertLimitation(
        failure: AdditionProofIncompleteException,
        limitation: AdditionProofLimitation,
    ) = assertEquals(listOf(limitation), failure.limitations)
}
