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
import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.query.ExactFileImageQuery
import io.github.amichne.kast.api.contract.query.MutationPostconditionAuthority
import io.github.amichne.kast.api.contract.query.MutationPostconditionQuery
import io.github.amichne.kast.api.contract.query.ReplacementPlanQuery
import io.github.amichne.kast.api.contract.result.ExactReplacementProof
import io.github.amichne.kast.api.contract.result.MutationPostconditionResult
import io.github.amichne.kast.api.contract.result.MutationPostconditionStatus
import io.github.amichne.kast.api.contract.result.ReplacementOutboundTarget
import io.github.amichne.kast.api.contract.result.ReplacementPlanResult
import io.github.amichne.kast.api.protocol.MutationPostconditionFailedException
import io.github.amichne.kast.api.protocol.MutationPostconditionLimitation
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
class KastReplacementPostconditionRefreshTest {
    companion object {
        private val defaultLimits = ServerLimits(
            maxResults = 500,
            requestTimeoutMillis = 30_000L,
            maxConcurrentRequests = 4,
        )

        private const val replacementSource = """
            package admission.replacement

            fun replacementFunction(value: String): String = value

            fun choose(value: String): String = value
            fun choose(value: Int): String = "int"
        """
    }

    private val projectFixture: TestFixture<Project> = projectFixture(openAfterCreation = true)
    private val moduleFixture: TestFixture<Module> = projectFixture.moduleFixture("main")
    private val productionRootFixture: TestFixture<PsiDirectory> = moduleFixture.sourceRootFixture()
    private val testRootFixture: TestFixture<PsiDirectory> = moduleFixture.sourceRootFixture(isTestSource = true)
    private val replacementFileFixture: TestFixture<PsiFile> =
        productionRootFixture.psiFileFixture("ReplacementPostcondition.kt", replacementSource)

    private val project: Project
        get() = projectFixture.get()

    private val workspaceRoot: Path
        get() = commonWorkspaceRoot(
            Path.of(productionRootFixture.get().virtualFile.path).toAbsolutePath().normalize(),
            Path.of(testRootFixture.get().virtualFile.path).toAbsolutePath().normalize(),
        )

    private fun backend(): KastIndexerBackend = KastIndexerBackend(
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
        moduleFixture.get()
        productionRootFixture.get()
        testRootFixture.get()
        replacementFileFixture.get()
        waitUntilIndexesAreReady(project)
    }

    private suspend fun planReplacement(
        backend: KastIndexerBackend,
        file: PsiFile,
        proposedDeclaration: String,
    ): ReplacementPlanResult {
        val target = readAction {
            SymbolIdentity(
                fqName = "admission.replacement.replacementFunction",
                kind = SymbolKind.FUNCTION,
                declarationFile = NormalizedPath.parse(file.virtualFile.path),
                declarationStartOffset = NonNegativeInt(file.text.indexOf("replacementFunction")),
            )
        }
        return backend.planReplacement(ReplacementPlanQuery(target, proposedDeclaration))
    }

    private suspend fun applyReplacement(backend: KastIndexerBackend, image: ExactFileImage) {
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

    private suspend fun verifyReplacement(
        backend: KastIndexerBackend,
        proof: ExactReplacementProof,
        edit: TextEdit,
        images: List<ExactFileImage>,
    ): MutationPostconditionResult = backend.verifyMutationPostcondition(
        MutationPostconditionQuery(
            MutationPostconditionAuthority.Replacement(proof, edit, images),
        ).parsed(),
    )

    @Test
    fun `replacement postcondition verifier proves exact signature and never writes`() = runBlocking {
        ensureProjectReady()
        val file = replacementFileFixture.get()
        val backend = backend()
        val plan = planReplacement(
            backend,
            file,
            "fun replacementFunction(value: String): String = value + \"\"",
        )
        val before = Files.readAllBytes(Path.of(file.virtualFile.path))
        val unapplied = runCatching {
            verifyReplacement(backend, plan.proof, plan.edit, plan.fileImages)
        }.exceptionOrNull() as? MutationPostconditionFailedException
            ?: error("Expected an unapplied replacement to fail its exact postimage check")
        assertEquals(listOf(MutationPostconditionLimitation.POSTIMAGE_MISMATCH), unapplied.limitations)
        assertArrayEquals(before, Files.readAllBytes(Path.of(file.virtualFile.path)))

        applyReplacement(backend, plan.fileImages.single())
        val postimage = Files.readAllBytes(Path.of(file.virtualFile.path))
        val verified = verifyReplacement(backend, plan.proof, plan.edit, plan.fileImages)

        assertEquals(MutationPostconditionStatus.VERIFIED, verified.status)
        assertArrayEquals(postimage, Files.readAllBytes(Path.of(file.virtualFile.path)))
    }

    @Test
    fun `replacement postcondition verifier preserves a terminal line feed outside the declaration slice`() =
        runBlocking {
            ensureProjectReady()
            val file = replacementFileFixture.get()
            val backend = backend()
            val proposed = "\nfun replacementFunction(value: String): String = value + \"\"\n"
            val plan = planReplacement(backend, file, proposed)

            assertEquals(1, plan.proof.declarationSlice.startOffset.value)
            assertEquals(proposed.length - 1, plan.proof.declarationSlice.endOffset.value)

            applyReplacement(backend, plan.fileImages.single())
            val verified = verifyReplacement(backend, plan.proof, plan.edit, plan.fileImages)

            assertEquals(MutationPostconditionStatus.VERIFIED, verified.status)
            assertEquals(proposed, plan.edit.newText)
        }

    @Test
    fun `replacement postcondition verifier retains preimage identity for a shifted same-file outbound target`() =
        runBlocking {
            ensureProjectReady()
            val file = replacementFileFixture.get()
            val backend = backend()
            val plan = planReplacement(
                backend,
                file,
                "fun replacementFunction(value: String): String = choose(value) + choose(value)",
            )

            applyReplacement(backend, plan.fileImages.single())
            val verified = verifyReplacement(backend, plan.proof, plan.edit, plan.fileImages)

            assertEquals(MutationPostconditionStatus.VERIFIED, verified.status)
            assertTrue(plan.proof.outboundReferences.any { reference ->
                (reference.resolvedTarget as? ReplacementOutboundTarget.Source)
                    ?.symbol?.fqName == "admission.replacement.choose"
            })
        }

    private fun commonWorkspaceRoot(first: Path, second: Path): Path =
        generateSequence(first) { it.parent }
            .first(second::startsWith)
}
