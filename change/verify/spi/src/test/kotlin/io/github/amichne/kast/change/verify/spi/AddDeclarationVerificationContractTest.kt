package io.github.amichne.kast.change.verify.spi

import io.github.amichne.kast.change.contract.AddDeclarationCompilerContextFile
import io.github.amichne.kast.change.contract.AddDeclarationClasspathFingerprint
import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationOutboundReferenceCount
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidence
import io.github.amichne.kast.change.contract.AddDeclarationProjectModelFingerprint
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
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.security.MessageDigest
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class AddDeclarationVerificationContractTest {
    @Test
    fun `command admits only a strictly newer exact publication`() {
        val plan = plan()
        val current = publication(7)
        val next = publication(8)

        val notNewer = assertInstanceOf<
            AddDeclarationVerificationCommandFailure.ResultGenerationNotNewer,
            >(
            assertInstanceOf<Refinement.Rejected<AddDeclarationVerificationCommandFailure>>(
                AddDeclarationVerificationCommand.admit(plan, current),
            ).failure,
        )
        assertEquals(plan.generation, notNewer.planned)
        assertEquals(current, notNewer.published)
        val admitted = assertInstanceOf<Refinement.Refined<AddDeclarationVerificationCommand>>(
            AddDeclarationVerificationCommand.admit(plan, next),
        ).value
        assertEquals(plan, admitted.plan)
        assertEquals(next, admitted.publication)
    }

    @Test
    fun `scoped compiler result is bound to the exact published generation`() {
        val command = AddDeclarationVerificationCommand.admit(plan(), publication(8)).refined()
        val identity = identity(command)

        val failure = assertInstanceOf<Refinement.Rejected<ObservedAddDeclarationVerificationFailure>>(
            ObservedAddDeclarationVerification.fromScopedCompilerRead(
                command,
                compilerContext(9, POSTIMAGE_HASH),
                identity,
                AddDeclarationCompilerDiagnosticsObservation.CLEAR,
                AddDeclarationCollisionObservation.ABSENT_COMPLETE,
                AddDeclarationOutboundBindingsObservation.PRESERVED_COMPLETE,
                AddDeclarationExistingBindingsObservation.PRESERVED_NO_CANDIDATES,
            ),
        ).failure

        assertEquals(ObservedAddDeclarationVerificationFailure.RESULT_GENERATION_MISMATCH, failure)
    }

    @Test
    fun `verified result carries matched identity publication and exact obligations`() {
        val command = AddDeclarationVerificationCommand.admit(plan(), publication(8)).refined()
        val identity = identity(command)

        val verified = ObservedAddDeclarationVerification.fromScopedCompilerRead(
            command,
            compilerContext(8, POSTIMAGE_HASH),
            identity,
            AddDeclarationCompilerDiagnosticsObservation.CLEAR,
            AddDeclarationCollisionObservation.ABSENT_COMPLETE,
            AddDeclarationOutboundBindingsObservation.PRESERVED_COMPLETE,
            AddDeclarationExistingBindingsObservation.PRESERVED_NO_CANDIDATES,
        ).refined()

        assertEquals(command.publication, verified.publication)
        assertEquals(command.plan.expectedSemanticDelta, verified.expectedSemanticDelta)
        assertEquals(command.plan.verification.obligations, verified.satisfiedObligations.values)
        assertEquals(identity, verified.identity)
    }

    private fun identity(
        command: AddDeclarationVerificationCommand,
    ): AddDeclarationObservedIdentity = AddDeclarationObservedIdentity.admit(
        command.plan.expectedSemanticDelta,
        expectedTargetPath = command.plan.target.targetPath,
        observedPackageName = "sample",
        observedDeclarationName = "added",
        observedKind = AddDeclarationKind.FUNCTION,
        observedStartOffset = 16,
        observedEndOffset = 36,
    ).refined()

    @Test
    fun `identity mismatch remains finite typed rejection`() {
        val expected = plan().expectedSemanticDelta

        assertEquals(
            AddDeclarationObservedIdentityFailure.DECLARATION_KIND_MISMATCH,
            assertInstanceOf<Refinement.Rejected<AddDeclarationObservedIdentityFailure>>(
                AddDeclarationObservedIdentity.admit(
                    expected,
                    expectedTargetPath = plan().target.targetPath,
                    observedPackageName = "sample",
                    observedDeclarationName = "added",
                    observedKind = AddDeclarationKind.PROPERTY,
                    observedStartOffset = 16,
                    observedEndOffset = 36,
                ),
            ).failure,
        )
    }

    private fun plan(): PlannedAddDeclaration {
        val before = "package sample\n".toByteArray()
        val after = "package sample\n\nfun added(): Int = 1\n".toByteArray()
        val intent = RawAddDeclarationPlanRequest(
            workspaceRoot = ROOT,
            targetPath = TARGET,
            expectedCurrentSha256 = hash(before),
            proposedDeclaration = "fun added(): Int = 1",
        ).refine().refined()
        val owner = AddDeclarationSourceOwner.admit(
            sourceRoot = "$ROOT/indexer/src/main/kotlin",
            ideaModuleName = "kast.indexer.main",
            gradleBuildRoot = ROOT,
            gradleProjectPath = ":indexer",
            sourceSetName = "main",
        ).refined()
        val target = AddDeclarationTargetCapability.admit(intent, owner).refined()
        val generation = generation(7)
        return PlannedAddDeclaration.issue(
            AddDeclarationPlanningEvidence.admit(
                intent = intent,
                generation = generation,
                target = target,
                expectedFile = ExpectedFileProof.admit(target, exact(before), exact(after)).refined(),
                declaredWriteSet = DeclaredWriteSet.admit(listOf(target.targetPath)).refined(),
                expectedSemanticDelta = ExpectedAddDeclarationDelta.admit(
                    "sample",
                    "added",
                    AddDeclarationKind.FUNCTION,
                ).refined(),
                verification = AddDeclarationVerificationContract.forGeneration(generation),
                compilerContext = compilerContext(7, hash(before)),
                compilerEvidence = DetachedCompilerEvidence.admit("{\"proof\":\"complete\"}").refined(),
            ).refined(),
        )
    }

    private fun compilerContext(
        rawGeneration: Long,
        targetHash: String,
    ): ExpectedAddDeclarationCompilerContext = ExpectedAddDeclarationCompilerContext.admit(
        generation = generation(rawGeneration),
        projectModelFingerprint = AddDeclarationProjectModelFingerprint.parse("3".repeat(64)).refined(),
        classpathFingerprint = AddDeclarationClasspathFingerprint.parse("4".repeat(64)).refined(),
        contextFiles = listOf(AddDeclarationCompilerContextFile.admit(TARGET, targetHash).refined()),
        outboundReferenceCount = AddDeclarationOutboundReferenceCount.parse(0).refined(),
    ).refined()

    private fun publication(value: Long): PublishedWorkspaceGeneration =
        PublishedWorkspaceGeneration(generation(value), WorkspaceStateIdentity("workspace-$value"))

    private fun generation(value: Long): EvidenceGeneration = EvidenceGeneration.parse(value).refined()

    private fun exact(bytes: ByteArray): ExactFileContentProof = ExactFileContentProof.admit(
        hash(bytes),
        Base64.getEncoder().encodeToString(bytes),
    ).refined()

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun <T, F> Refinement<T, F>.refined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value

    private companion object {
        const val ROOT = "/workspace/kast"
        const val TARGET = "$ROOT/indexer/src/main/kotlin/sample/Target.kt"
        val POSTIMAGE_HASH: String = MessageDigest.getInstance("SHA-256")
            .digest("package sample\n\nfun added(): Int = 1\n".toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
