package io.github.amichne.kast.idea

import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.query.ExactFileImageQuery
import io.github.amichne.kast.api.contract.query.MutationPostconditionAuthority
import io.github.amichne.kast.api.contract.query.MutationPostconditionQuery
import io.github.amichne.kast.api.contract.query.ReplacementPlanQuery
import io.github.amichne.kast.api.contract.result.MutationPostconditionEvidence
import io.github.amichne.kast.api.contract.result.MutationPostconditionOperation
import io.github.amichne.kast.api.contract.result.MutationPostconditionStatus
import io.github.amichne.kast.api.contract.result.ReplacementPlanResult
import io.github.amichne.kast.api.contract.result.ReplacementOutboundTarget
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.ReplacementProofIncompleteException
import io.github.amichne.kast.api.protocol.ReplacementProofLimitation
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
internal class ExactDeclarationBodyReplacementPublicRouteTest {
    companion object {
        private val defaultLimits = ServerLimits(
            maxResults = 500,
            requestTimeoutMillis = 30_000L,
            maxConcurrentRequests = 4,
        )
    }

    private val projectFixture: TestFixture<Project> = projectFixture(openAfterCreation = true)
    private val mainModuleFixture: TestFixture<Module> = projectFixture.moduleFixture("main")
    private val mainSourceRootFixture: TestFixture<PsiDirectory> = mainModuleFixture.sourceRootFixture()
    private val bodyFixture: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "ExactDeclarationBodyReplacement.kt",
        """
            package demo.body

            fun transform(value: String): String {
                return value
            }

            fun transform(value: Int): String {
                return value.toString()
            }

            class SameNameOwner {
                fun transform(value: String): String = value.reversed()
            }
        """.trimIndent(),
    )
    private val project: Project
        get() = projectFixture.get()
    private val contextFixture: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "ExactDeclarationBodyContext.kt",
        """
            package demo.body

            fun unchangedContext(): String = "context"
        """.trimIndent(),
    )

    @Test
    fun `public replacement planning authorizes only the exact selected function body`() = runBlocking {
        ensureProjectReady()
        val file = bodyFixture.get()
        val source = readAction { file.text }
        val selectedNameOffset = source.indexOf("transform(value: String)")
        val selectedBodyStart = source.indexOf('{', selectedNameOffset)
        val selectedBodyEnd = source.indexOf('}', selectedBodyStart) + 1
        val proposed = """
            fun transform(value: String): String {
                return "changed"
            }
        """.trimIndent()
        val proposedBody = proposed.substring(proposed.indexOf('{'))
        val workspaceRoot = workspaceRoot(file)

        val result = backend(workspaceRoot = workspaceRoot).planReplacement(
            ReplacementPlanQuery(
                target = SymbolIdentity(
                    fqName = "demo.body.transform",
                    kind = SymbolKind.FUNCTION,
                    declarationFile = NormalizedPath.parse(file.virtualFile.path),
                    declarationStartOffset = NonNegativeInt(selectedNameOffset),
                ),
                proposedDeclaration = proposed,
            ),
        )

        assertEquals(selectedBodyStart, result.edit.startOffset)
        assertEquals(selectedBodyEnd, result.edit.endOffset)
        assertEquals(proposedBody, result.edit.newText)
        assertEquals(FileHashing.sha256(proposed), result.proof.proposedDeclarationHash.value)
        assertEquals(FileHashing.sha256(proposedBody), result.proof.proposedBodyHash.value)
        assertEquals(
            proposedBody,
            proposed.substring(
                result.proof.proposedBodySlice.startOffset.value,
                result.proof.proposedBodySlice.endOffset.value,
            ),
        )
        assertNotEquals(result.proof.proposedDeclarationHash.value, result.proof.proposedBodyHash.value)
        assertEquals(source, readAction { file.text })
    }

    @Test
    fun `request-only signature trivia cannot shift exact body postimage targets`() = runBlocking {
        ensureProjectReady()
        val file = bodyFixture.get()
        val source = readAction { file.text }
        val selectedNameOffset = source.indexOf("transform(value: String)")
        val selectedBodyStart = source.indexOf('{', selectedNameOffset)
        val selectedBodyEnd = source.indexOf('}', selectedBodyStart) + 1
        val laterNameOffset = source.indexOf("transform(value: Int)")
        val proposed = """
            fun /* request-only */ transform /* request-only */ (value: String): String /* request-only */ {
                return transform(1)
            }
        """.trimIndent()
        val proposedBody = proposed.substring(proposed.indexOf('{'))
        val backend = backend(workspaceRoot(file))

        val plan = backend.planReplacement(
            ReplacementPlanQuery(
                target = SymbolIdentity(
                    fqName = "demo.body.transform",
                    kind = SymbolKind.FUNCTION,
                    declarationFile = NormalizedPath.parse(file.virtualFile.path),
                    declarationStartOffset = NonNegativeInt(selectedNameOffset),
                ),
                proposedDeclaration = proposed,
            ),
        )

        assertEquals(selectedBodyStart, plan.edit.startOffset)
        assertEquals(selectedBodyEnd, plan.edit.endOffset)
        assertEquals(proposedBody, plan.edit.newText)
        assertEquals(FileHashing.sha256(proposed), plan.proof.proposedDeclarationHash.value)
        assertEquals(
            proposedBody,
            proposed.substring(
                plan.proof.proposedBodySlice.startOffset.value,
                plan.proof.proposedBodySlice.endOffset.value,
            ),
        )
        val outbound = plan.proof.outboundReferences.single { reference -> reference.sourceText == "transform" }
        val target = outbound.resolvedTarget as ReplacementOutboundTarget.Source
        assertEquals(laterNameOffset, target.symbol.declarationStartOffset.value)
        val expectedPostimage = source.replaceRange(selectedBodyStart, selectedBodyEnd, proposedBody)
        assertEquals(expectedPostimage, plan.fileImages.single().postimage.copyBytes().toString(Charsets.UTF_8))

        applyPlan(backend, plan)
        assertEquals(expectedPostimage, readAction { file.text })
        val receipt = backend.verifyMutationPostcondition(
            MutationPostconditionQuery(
                MutationPostconditionAuthority.Replacement(plan.proof, plan.edit, plan.fileImages),
            ).parsed(),
        )
        assertEquals(MutationPostconditionStatus.VERIFIED, receipt.status)
    }

    @Test
    fun `stale selector cannot drift to another overload or same-name owner`() = runBlocking {
        ensureProjectReady()
        val file = bodyFixture.get()
        val source = readAction { file.text }
        val staleOffset = source.indexOf("transform(value: String)") + 1
        val failure = replacementFailure(
            file = file,
            declarationOffset = staleOffset,
            fqName = "demo.body.transform",
            proposed = "fun transform(value: String): String = \"changed\"",
        )

        assertLimitation(failure, ReplacementProofLimitation.TARGET_IDENTITY_UNPROVEN)
    }

    @Test
    fun `invalid syntax and unresolved body binding fail closed`() = runBlocking {
        ensureProjectReady()
        val file = bodyFixture.get()
        val source = readAction { file.text }
        val declarationOffset = source.indexOf("transform(value: String)")

        assertLimitation(
            replacementFailure(
                file,
                declarationOffset,
                "demo.body.transform",
                "fun transform(value: String): String { return",
            ),
            ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
        )
        assertLimitation(
            replacementFailure(
                file,
                declarationOffset,
                "demo.body.transform",
                "fun transform(value: String): String { return missing(value) }",
            ),
            ReplacementProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
        )
        assertLimitation(
            replacementFailure(
                file,
                declarationOffset,
                "demo.body.transform",
                "fun transform(value: String): String { return 42 }",
            ),
            ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
        )
        assertLimitation(
            replacementFailure(
                file,
                declarationOffset,
                "demo.body.transform",
                "fun transform(value: String): String = \"changed\"",
            ),
            ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_CONTENT,
        )
    }

    @Test
    fun `stale plan cannot apply after its exact preimage changes`() = runBlocking {
        ensureProjectReady()
        val file = bodyFixture.get()
        val plan = selectedPlan(file)
        val path = Path.of(file.virtualFile.path)
        Files.writeString(path, Files.readString(path) + "\n// stale")
        val image = plan.fileImages.single()

        assertThrows(ConflictException::class.java) {
            runBlocking {
                backend(workspaceRoot(file)).exactFileImageCas(
                    ExactFileImageQuery(
                        filePath = image.filePath,
                        expectedCurrentSha256 = image.preimage.sha256,
                        contentBase64 = image.postimage.contentBase64,
                        expectedResultSha256 = image.postimage.sha256,
                    ),
                )
            }
        }
        Unit
    }

    @Test
    fun `exact G1 route applies body write and returns verified semantic receipt`() = runBlocking {
        ensureProjectReady()
        val file = bodyFixture.get()
        val backend = backend(workspaceRoot(file))
        val plan = selectedPlan(file, backend)

        applyPlan(backend, plan)
        val receipt = backend.verifyMutationPostcondition(
            MutationPostconditionQuery(
                MutationPostconditionAuthority.Replacement(plan.proof, plan.edit, plan.fileImages),
            ).parsed(),
        )

        assertEquals(MutationPostconditionStatus.VERIFIED, receipt.status)
        assertEquals(MutationPostconditionOperation.REPLACEMENT, receipt.operation)
        val evidence = receipt.evidence as MutationPostconditionEvidence.Replacement
        assertEquals(plan.edit.startOffset, evidence.sourceRange.startOffset)
        assertEquals(plan.edit.endOffset, evidence.sourceRange.endOffset)
        assertEquals(plan.proof.proposedSignature, evidence.signature)
        assertEquals(plan.proof.outboundReferences, evidence.outboundReferences)
        assertEquals(plan.fileImages.single().postimage.sha256, receipt.postimages.single().sha256)
    }

    private suspend fun selectedPlan(
        file: PsiFile,
        backend: io.github.amichne.kast.idea.backend.KastIndexerBackend = backend(workspaceRoot(file)),
    ): ReplacementPlanResult {
        val declarationOffset = readAction { file.text.indexOf("transform(value: String)") }
        return backend.planReplacement(
            ReplacementPlanQuery(
                target = SymbolIdentity(
                    fqName = "demo.body.transform",
                    kind = SymbolKind.FUNCTION,
                    declarationFile = NormalizedPath.parse(file.virtualFile.path),
                    declarationStartOffset = NonNegativeInt(declarationOffset),
                ),
                proposedDeclaration = "fun transform(value: String): String { return \"changed\" }",
            ),
        )
    }

    private suspend fun applyPlan(
        backend: KastIndexerBackend,
        plan: ReplacementPlanResult,
    ) {
        val image = plan.fileImages.single()
        backend.exactFileImageCas(
            ExactFileImageQuery(
                filePath = image.filePath,
                expectedCurrentSha256 = image.preimage.sha256,
                contentBase64 = image.postimage.contentBase64,
                expectedResultSha256 = image.postimage.sha256,
            ),
        )
        waitUntilIndexesAreReady(project)
    }

    private fun backend(workspaceRoot: Path): KastIndexerBackend = KastIndexerBackend(
        project = project,
        workspaceRoot = workspaceRoot,
        limits = defaultLimits,
        semanticAdmissionAwaiter = IdeaSemanticAdmissionAwaiter.forRequestBudget(
            defaultLimits.requestTimeoutMillis,
        ),
        semanticAdmissionOperations = IdeaSemanticAdmissionOperations.idea(),
        workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
        workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
        workspaceModelReader = { IdeaGradleProjectLoadBridge.readWorkspaceModel(project) },
    )

    private fun ensureProjectReady() {
        mainModuleFixture.get()
        mainSourceRootFixture.get()
        bodyFixture.get()
        contextFixture.get()
        waitUntilIndexesAreReady(project)
    }

    private fun workspaceRoot(file: PsiFile): Path =
        commonWorkspaceRoot(file.virtualFile.path, contextFixture.get().virtualFile.path)

    private fun commonWorkspaceRoot(
        first: String,
        second: String
    ): Path {
        val firstPath = Path.of(first).toAbsolutePath().normalize()
        val secondPath = Path.of(second).toAbsolutePath().normalize()
        return generateSequence(firstPath.parent) { it.parent }
            .first { candidate -> secondPath.startsWith(candidate) }
    }

    private suspend fun replacementFailure(
        file: PsiFile,
        declarationOffset: Int,
        fqName: String,
        proposed: String,
    ): Throwable? = runCatching {
        backend(workspaceRoot(file)).planReplacement(
            ReplacementPlanQuery(
                target = SymbolIdentity(
                    fqName = fqName,
                    kind = SymbolKind.FUNCTION,
                    declarationFile = NormalizedPath.parse(file.virtualFile.path),
                    declarationStartOffset = NonNegativeInt(declarationOffset),
                ),
                proposedDeclaration = proposed,
            ),
        )
    }.exceptionOrNull()

    private fun assertLimitation(
        failure: Throwable?,
        limitation: ReplacementProofLimitation
    ) {
        val exact = failure as? ReplacementProofIncompleteException
                    ?: error("Expected replacement proof failure, got $failure")
        assertTrue(limitation in exact.evidence.limitations)
    }
}
