package io.github.amichne.kast.change.verify.service

import io.github.amichne.kast.change.journal.contract.LoadAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.VerifiedAddDeclaration
import io.github.amichne.kast.change.verify.service.AddDeclarationVerificationServiceTestSupport.applied
import io.github.amichne.kast.change.verify.service.AddDeclarationVerificationServiceTestSupport.context
import io.github.amichne.kast.change.verify.service.AddDeclarationVerificationServiceTestSupport.hash
import io.github.amichne.kast.change.verify.service.AddDeclarationVerificationServiceTestSupport.identity
import io.github.amichne.kast.change.verify.service.AddDeclarationVerificationServiceTestSupport.open
import io.github.amichne.kast.change.verify.service.AddDeclarationVerificationServiceTestSupport.publication
import io.github.amichne.kast.change.verify.service.AddDeclarationVerificationServiceTestSupport.refined
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationExecutor
import io.github.amichne.kast.change.verify.spi.AddDeclarationCompilerDiagnosticsObservation
import io.github.amichne.kast.change.verify.spi.AddDeclarationCollisionObservation
import io.github.amichne.kast.change.verify.spi.AddDeclarationExistingBindingsObservation
import io.github.amichne.kast.change.verify.spi.AddDeclarationOutboundBindingsObservation
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationCommand
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationLimitation
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationRejection
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationResult
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationJournal
import io.github.amichne.kast.change.verify.spi.CompleteAddDeclarationVerification
import io.github.amichne.kast.change.verify.spi.CompleteAddDeclarationVerificationResult
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshnessClaim
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshnessClaims
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionRequest
import io.github.amichne.kast.workspace.spi.WorkspaceMutationTransitionOutcome
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionFailure
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionOutcome
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionPort
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class AddDeclarationVerificationServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `exact postimage claim publishes and yields durable typed receipt`() = runTest {
        val journal = open(tempDir.resolve("verified.db"))
        val applied = applied(journal)
        val publication = publication(8)
        val port = CapturingTransitionPort(WorkspaceTransitionOutcome.Published(publication))
        val service = AddDeclarationVerificationService(
            transitions = port,
            executor = SuccessfulExecutor(),
            journal = journal,
        )

        val result = assertInstanceOf<VerifyAppliedAddDeclarationResult.Verified>(
            service.verify(applied),
        )

        assertEquals(expectedRequest(), port.request)
        assertEquals(publication, result.observation.publication)
        assertEquals(publication, result.record.receipt.publication)
        assertEquals(result.observation.identity.targetPath, result.record.receipt.identity.targetPath)
        assertEquals(applied.afterImage.sha256, result.record.receipt.postimageSha256)
        assertEquals(
            result.record,
            assertInstanceOf<LoadAddDeclarationPlanResult.Found>(
                journal.load(applied.plan.planId),
            ).record,
        )
        assertInstanceOf<VerifiedAddDeclaration>(result.record)
    }

    @Test
    fun `transition rejection retains exact applied capability`() = runTest {
        val journal = open(tempDir.resolve("transition-rejected.db"))
        val applied = applied(journal)
        val service = AddDeclarationVerificationService(
            transitions = CapturingTransitionPort(
                WorkspaceTransitionOutcome.Rejected(WorkspaceTransitionFailure.NotAttached),
            ),
            executor = RejectingExecutor(
                AddDeclarationVerificationRejection.of(
                    AddDeclarationVerificationLimitation.SEMANTIC_READ_UNAVAILABLE,
                ),
            ),
            journal = journal,
        )

        val result = assertInstanceOf<VerifyAppliedAddDeclarationResult.RejectedBeforePublication>(
            service.verify(applied),
        )

        assertEquals(applied, result.applied)
        assertEquals(
            AddDeclarationBeforePublicationFailure.TransitionRejected(
                WorkspaceTransitionFailure.NotAttached,
            ),
            result.failure,
        )
    }

    @Test
    fun `semantic rejection retains applied capability and exact publication`() = runTest {
        val journal = open(tempDir.resolve("semantic-rejected.db"))
        val applied = applied(journal)
        val publication = publication(8)
        val rejection = AddDeclarationVerificationRejection.of(
            AddDeclarationVerificationLimitation.COMPILER_DIAGNOSTICS_REJECTED,
        )
        val service = AddDeclarationVerificationService(
            transitions = CapturingTransitionPort(WorkspaceTransitionOutcome.Published(publication)),
            executor = RejectingExecutor(rejection),
            journal = journal,
        )

        val result = assertInstanceOf<VerifyAppliedAddDeclarationResult.RejectedAfterPublication>(
            service.verify(applied),
        )

        assertEquals(applied, result.applied)
        assertEquals(publication, result.publication)
        assertEquals(
            AddDeclarationAfterPublicationFailure.VerificationRejected(rejection),
            result.failure,
        )
    }

    @Test
    fun `observation for another command is rejected as a typed protocol mismatch`() = runTest {
        val journal = open(tempDir.resolve("wrong-command.db"))
        val applied = applied(journal)
        val publication = publication(8)
        val service = AddDeclarationVerificationService(
            transitions = CapturingTransitionPort(WorkspaceTransitionOutcome.Published(publication)),
            executor = OtherCommandExecutor(publication(9)),
            journal = journal,
        )

        val result = assertInstanceOf<VerifyAppliedAddDeclarationResult.RejectedAfterPublication>(
            service.verify(applied),
        )

        assertEquals(applied, result.applied)
        assertEquals(publication, result.publication)
        assertEquals(
            AddDeclarationAfterPublicationFailure.VerificationCommandMismatch,
            result.failure,
        )
    }

    @Test
    fun `lost receipt acknowledgement retains verified observation and recovery authority`() = runTest {
        val stored = open(tempDir.resolve("unknown.db"))
        val applied = applied(stored)
        val journal = object : AddDeclarationVerificationJournal by stored {
            override fun completeVerification(
                command: CompleteAddDeclarationVerification,
            ): CompleteAddDeclarationVerificationResult =
                CompleteAddDeclarationVerificationResult.CommitOutcomeUnknown(command.applied.plan.planId)
        }
        val publication = publication(8)
        val service = AddDeclarationVerificationService(
            transitions = CapturingTransitionPort(WorkspaceTransitionOutcome.Published(publication)),
            executor = SuccessfulExecutor(),
            journal = journal,
        )

        val result = assertInstanceOf<
            VerifyAppliedAddDeclarationResult.CompletionReconciliationRequired,
            >(service.verify(applied))

        assertEquals(applied, result.applied)
        assertEquals(publication, result.observation.publication)
    }

    @Test
    fun `workspace transition cancellation is rethrown without manufacturing rejection`() = runTest {
        val journal = open(tempDir.resolve("transition-cancelled.db"))
        val applied = applied(journal)
        val cancellation = CancellationException("transition cancelled")
        val service = AddDeclarationVerificationService(
            transitions = CancellingTransitionPort(cancellation),
            executor = SuccessfulExecutor(),
            journal = journal,
        )

        val thrown = runCatching { service.verify(applied) }.exceptionOrNull()

        assertSame(cancellation, thrown)
    }

    @Test
    fun `verification cancellation is rethrown without manufacturing rejection`() = runTest {
        val journal = open(tempDir.resolve("verification-cancelled.db"))
        val applied = applied(journal)
        val publication = publication(8)
        val cancellation = CancellationException("verification cancelled")
        val service = AddDeclarationVerificationService(
            transitions = CapturingTransitionPort(WorkspaceTransitionOutcome.Published(publication)),
            executor = CancellingExecutor(cancellation),
            journal = journal,
        )

        val thrown = runCatching { service.verify(applied) }.exceptionOrNull()

        assertSame(cancellation, thrown)
    }

    private fun expectedRequest(): WorkspaceTransitionRequest.SourceFiles {
        val path = WorkspaceSourcePath.parse(
            "indexer/src/main/kotlin/sample/Target.kt",
        ).refined()
        val content = WorkspaceSourceContentHash.parse(
            hash(AddDeclarationVerificationServiceTestSupport.AFTER),
        ).refined()
        val claims = WorkspaceSourceFreshnessClaims.refine(
            listOf(WorkspaceSourceFreshnessClaim(path, WorkspaceSourceContentIdentity.Present(content))),
        ).refined()
        return WorkspaceTransitionRequest.SourceFiles(claims)
    }

    private class CapturingTransitionPort(
        private val outcome: WorkspaceTransitionOutcome,
    ) : WorkspaceTransitionPort {
        var request: WorkspaceTransitionRequest? = null
            private set

        override suspend fun reconcile(request: WorkspaceTransitionRequest): WorkspaceTransitionOutcome {
            this.request = request
            return outcome
        }

        override suspend fun <Value> mutate(
            signal: io.github.amichne.kast.workspace.contract.WorkspaceSignal,
            detail: String,
            operation: suspend () -> Value,
        ): WorkspaceMutationTransitionOutcome<Value> =
            WorkspaceMutationTransitionOutcome.Rejected(
                io.github.amichne.kast.workspace.spi.WorkspaceMutationTransitionFailure
                    .ReconciliationRejected(WorkspaceTransitionFailure.Closed),
            )
    }

    private class CancellingTransitionPort(
        private val cancellation: CancellationException,
    ) : WorkspaceTransitionPort {
        override suspend fun reconcile(
            request: WorkspaceTransitionRequest,
        ): WorkspaceTransitionOutcome = throw cancellation

        override suspend fun <Value> mutate(
            signal: io.github.amichne.kast.workspace.contract.WorkspaceSignal,
            detail: String,
            operation: suspend () -> Value,
        ): WorkspaceMutationTransitionOutcome<Value> = throw cancellation
    }

    private class SuccessfulExecutor : AddDeclarationVerificationExecutor() {
        override suspend fun verify(
            command: AddDeclarationVerificationCommand,
        ): AddDeclarationVerificationResult = verified(
            command,
            context(command),
            identity(command),
            AddDeclarationCompilerDiagnosticsObservation.CLEAR,
            AddDeclarationCollisionObservation.ABSENT_COMPLETE,
            AddDeclarationOutboundBindingsObservation.PRESERVED_COMPLETE,
            AddDeclarationExistingBindingsObservation.PRESERVED_NO_CANDIDATES,
        )
    }

    private class RejectingExecutor(
        private val rejection: AddDeclarationVerificationRejection,
    ) : AddDeclarationVerificationExecutor() {
        override suspend fun verify(
            command: AddDeclarationVerificationCommand,
        ): AddDeclarationVerificationResult = AddDeclarationVerificationResult.Rejected(
            command,
            rejection,
        )
    }

    private class CancellingExecutor(
        private val cancellation: CancellationException,
    ) : AddDeclarationVerificationExecutor() {
        override suspend fun verify(
            command: AddDeclarationVerificationCommand,
        ): AddDeclarationVerificationResult = throw cancellation
    }

    private class OtherCommandExecutor(
        private val otherPublication: io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration,
    ) : AddDeclarationVerificationExecutor() {
        override suspend fun verify(
            command: AddDeclarationVerificationCommand,
        ): AddDeclarationVerificationResult {
            val other = AddDeclarationVerificationCommand.admit(
                command.plan,
                otherPublication,
            ).refined()
            return verified(
                other,
                context(other),
                identity(other),
                AddDeclarationCompilerDiagnosticsObservation.CLEAR,
                AddDeclarationCollisionObservation.ABSENT_COMPLETE,
                AddDeclarationOutboundBindingsObservation.PRESERVED_COMPLETE,
                AddDeclarationExistingBindingsObservation.PRESERVED_NO_CANDIDATES,
            )
        }
    }
}
