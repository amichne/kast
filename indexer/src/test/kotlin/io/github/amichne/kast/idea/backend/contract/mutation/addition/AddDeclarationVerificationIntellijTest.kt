package io.github.amichne.kast.idea

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidence
import io.github.amichne.kast.change.contract.AddDeclarationSourceOwner
import io.github.amichne.kast.change.contract.AddDeclarationTargetCapability
import io.github.amichne.kast.change.contract.AddDeclarationVerificationContract
import io.github.amichne.kast.change.contract.DeclaredWriteSet
import io.github.amichne.kast.change.contract.DetachedCompilerEvidence
import io.github.amichne.kast.change.contract.ExactFileContentProof
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationCompilerContext
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.ExpectedFileProof
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.contract.RawAddDeclarationPlanRequest
import io.github.amichne.kast.change.verify.intellij.IntellijAddDeclarationCompilerEnvironment
import io.github.amichne.kast.change.verify.intellij.IntellijAddDeclarationCompilerEnvironmentResult
import io.github.amichne.kast.change.verify.intellij.IntellijAddDeclarationVerificationExecutor
import io.github.amichne.kast.change.verify.intellij.IntellijPublishedWorkspaceGenerationAuthority
import io.github.amichne.kast.change.verify.spi.AddDeclarationObservedSourceRange
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationCommand
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationLimitation
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationResult
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

@TestApplication
internal class AddDeclarationVerificationIntellijTest : KastIndexerBackendContractTestFixture() {
    private val verifiedTargetFixture: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "VerifiedAddDeclaration.kt",
        postimage(VERIFIED_DECLARATION),
    )
    private val rejectedTargetFixture: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "RejectedAddDeclaration.kt",
        postimage(REJECTED_DECLARATION),
    )

    @Test
    fun `real PSI and K2 observe exact declaration identity at published generation`() = runBlocking {
        val fixture = fixture(verifiedTargetFixture, VERIFIED_DECLARATION, "verifiedAddition")

        val result = assertInstanceOf<AddDeclarationVerificationResult.Observed>(
            fixture.executor.verify(fixture.command),
        ).verification

        val startOffset = PREIMAGE.length + 1
        assertEquals(fixture.publication, result.publication)
        assertEquals(fixture.plan.target.targetPath, result.identity.targetPath)
        assertEquals(
            AddDeclarationObservedSourceRange.admit(
                startOffset,
                startOffset + VERIFIED_DECLARATION.length,
            ).refined(),
            result.identity.sourceRange,
        )
        assertEquals(AddDeclarationKind.FUNCTION, result.identity.declarationKind)
        assertEquals(
            fixture.publication.generation.value,
            result.compilerContext.generation.value,
        )
    }

    @Test
    fun `real compiler diagnostic is a sealed verification rejection`() = runBlocking {
        val fixture = fixture(rejectedTargetFixture, REJECTED_DECLARATION, "rejectedAddition")

        val result = assertInstanceOf<AddDeclarationVerificationResult.Rejected>(
            fixture.executor.verify(fixture.command),
        )

        assertEquals(
            listOf(AddDeclarationVerificationLimitation.COMPILER_DIAGNOSTICS_REJECTED),
            result.rejection.limitations,
        )
        assertEquals(fixture.command, result.command)
    }

    @Test
    fun `ReviewRegression rechecks publication immediately before verification return`() = runBlocking {
        val fixture = fixture(verifiedTargetFixture, VERIFIED_DECLARATION, "verifiedAddition")
        val moved = PublishedWorkspaceGeneration(
            generation(9),
            WorkspaceStateIdentity("verified-add-declaration-g2"),
        )
        val observations = ArrayDeque(
            listOf(
                PublishedWorkspaceGenerationState.Published(fixture.publication),
                PublishedWorkspaceGenerationState.Published(fixture.publication),
                PublishedWorkspaceGenerationState.Published(moved),
            ),
        )
        val executor = executor(
            fixture.plan,
            fixture.publication,
            publications = { observations.removeFirst() },
        )

        val result = assertInstanceOf<AddDeclarationVerificationResult.Rejected>(
            executor.verify(fixture.command),
        )

        assertEquals(
            listOf(AddDeclarationVerificationLimitation.RESULT_GENERATION_MOVED),
            result.rejection.limitations,
        )
    }

    @Test
    fun `platform cancellation is rethrown without manufacturing rejection`() {
        val fixture = fixture(verifiedTargetFixture, VERIFIED_DECLARATION, "verifiedAddition")
        val cancellation = ProcessCanceledException()
        val cancelling = executor(fixture.plan, fixture.publication) { throw cancellation }

        val thrown = assertThrows<ProcessCanceledException> {
            runBlocking { cancelling.verify(fixture.command) }
        }

        assertSame(cancellation, thrown)
    }

    private fun fixture(
        targetFixture: TestFixture<PsiFile>,
        declaration: String,
        declarationName: String,
    ): VerificationFixture {
        ensureProjectReady()
        val target = targetFixture.get() as KtFile
        waitUntilIndexesAreReady(project)
        val plan = plan(target, declaration, declarationName)
        val publication = PublishedWorkspaceGeneration(
            generation(8),
            WorkspaceStateIdentity("verified-add-declaration-g1"),
        )
        val command = AddDeclarationVerificationCommand.admit(plan, publication).refined()
        return VerificationFixture(
            plan,
            publication,
            command,
            executor(plan, publication),
        )
    }

    private fun executor(
        plan: PlannedAddDeclaration,
        publication: PublishedWorkspaceGeneration,
        publications: IntellijPublishedWorkspaceGenerationAuthority =
            IntellijPublishedWorkspaceGenerationAuthority {
                PublishedWorkspaceGenerationState.Published(publication)
            },
        beforeRead: () -> Unit = {},
    ): IntellijAddDeclarationVerificationExecutor = IntellijAddDeclarationVerificationExecutor(
        project = project,
        runtime = documentedIntellijIdeaRuntime,
        publications = publications,
        environment = {
            IntellijAddDeclarationCompilerEnvironmentResult.Observed(
                IntellijAddDeclarationCompilerEnvironment.observed(
                    plan.compilerContext.projectModelFingerprint,
                    plan.compilerContext.classpathFingerprint,
                    plan.target.owner,
                ),
            )
        },
        beforeRead = beforeRead,
    )

    private fun plan(
        targetFile: KtFile,
        declaration: String,
        declarationName: String,
    ): PlannedAddDeclaration {
        val targetPath = Path.of(targetFile.virtualFile.path).toAbsolutePath().normalize()
        val sourceRoot = targetPath.parent
        val workspaceRoot = commonWorkspaceRoot(
            sourceRoot.toString(),
            targetPath.toString(),
        )
        val intent = RawAddDeclarationPlanRequest(
            workspaceRoot.toString(),
            targetPath.toString(),
            hash(PREIMAGE_BYTES),
            declaration,
        ).refine().refined()
        val owner = AddDeclarationSourceOwner.admit(
            sourceRoot.toString(),
            "main",
            workspaceRoot.toString(),
            ":",
            "main",
        ).refined()
        val target = AddDeclarationTargetCapability.admit(intent, owner).refined()
        val generation = generation(7)
        val postimage = postimage(declaration).toByteArray()
        return PlannedAddDeclaration.issue(
            AddDeclarationPlanningEvidence.admit(
                intent,
                generation,
                target,
                ExpectedFileProof.admit(
                    target,
                    exact(PREIMAGE_BYTES),
                    exact(postimage),
                ).refined(),
                DeclaredWriteSet.admit(listOf(target.targetPath)).refined(),
                ExpectedAddDeclarationDelta.admit(
                    PACKAGE_NAME,
                    declarationName,
                    AddDeclarationKind.FUNCTION,
                ).refined(),
                AddDeclarationVerificationContract.forGeneration(generation),
                ExpectedAddDeclarationCompilerContext.admitSingleSource(
                    generation,
                    "3".repeat(64),
                    "4".repeat(64),
                    targetPath.toString(),
                    hash(PREIMAGE_BYTES),
                    0,
                ).refined(),
                DetachedCompilerEvidence.admit("{\"proof\":\"complete\"}").refined(),
            ).refined(),
        )
    }

    private fun exact(bytes: ByteArray): ExactFileContentProof = ExactFileContentProof.admit(
        hash(bytes),
        Base64.getEncoder().encodeToString(bytes),
    ).refined()

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun generation(value: Long): EvidenceGeneration = EvidenceGeneration.parse(value).refined()

    private fun <T, F> Refinement<T, F>.refined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value

    private data class VerificationFixture(
        val plan: PlannedAddDeclaration,
        val publication: PublishedWorkspaceGeneration,
        val command: AddDeclarationVerificationCommand,
        val executor: IntellijAddDeclarationVerificationExecutor,
    )

    private companion object {
        const val PACKAGE_NAME = "sample.verify"
        const val PREIMAGE = "package $PACKAGE_NAME\n"
        val PREIMAGE_BYTES: ByteArray = PREIMAGE.toByteArray()
        const val VERIFIED_DECLARATION = "fun verifiedAddition() {}"
        const val REJECTED_DECLARATION =
            "fun rejectedAddition(): MissingVerificationType = MissingVerificationType()"

        fun postimage(declaration: String): String = "$PREIMAGE\n$declaration\n"
    }
}
