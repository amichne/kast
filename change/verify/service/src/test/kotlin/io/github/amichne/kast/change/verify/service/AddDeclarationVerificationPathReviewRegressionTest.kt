package io.github.amichne.kast.change.verify.service

import io.github.amichne.kast.change.verify.service.AddDeclarationVerificationServiceTestSupport.ROOT
import io.github.amichne.kast.change.verify.service.AddDeclarationVerificationServiceTestSupport.applied
import io.github.amichne.kast.change.verify.service.AddDeclarationVerificationServiceTestSupport.hash
import io.github.amichne.kast.change.verify.service.AddDeclarationVerificationServiceTestSupport.open
import io.github.amichne.kast.change.verify.service.AddDeclarationVerificationServiceTestSupport.refined
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationCommand
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationExecutor
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationResult
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshnessClaim
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshnessClaims
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionRequest
import io.github.amichne.kast.workspace.spi.WorkspaceMutationTransitionFailure
import io.github.amichne.kast.workspace.spi.WorkspaceMutationTransitionOutcome
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionFailure
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionOutcome
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionPort
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AddDeclarationVerificationPathReviewRegressionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `verification publication preserves Unix backslash target identity`() = runTest {
        assumeTrue(Path.of("a\\b.kt").nameCount == 1)
        val relative = "indexer/src/main/kotlin/sample/a\\b.kt"
        val target = "$ROOT/$relative"
        val journal = open(tempDir.resolve("backslash-target.db"))
        val transition = CapturingRejectedTransition()
        val service = AddDeclarationVerificationService(
            transitions = transition,
            executor = UnexpectedVerificationExecutor,
            journal = journal,
        )

        service.verify(applied(journal, target))

        val path = WorkspaceSourcePath.parse(relative).refined()
        val content = WorkspaceSourceContentHash.parse(
            hash(AddDeclarationVerificationServiceTestSupport.AFTER),
        ).refined()
        val claims = WorkspaceSourceFreshnessClaims.refine(
            listOf(WorkspaceSourceFreshnessClaim(path, WorkspaceSourceContentIdentity.Present(content))),
        ).refined()
        assertEquals(WorkspaceTransitionRequest.SourceFiles(claims), transition.request)
    }

    private class CapturingRejectedTransition : WorkspaceTransitionPort {
        lateinit var request: WorkspaceTransitionRequest
            private set

        override suspend fun reconcile(request: WorkspaceTransitionRequest): WorkspaceTransitionOutcome {
            this.request = request
            return WorkspaceTransitionOutcome.Rejected(WorkspaceTransitionFailure.Closed)
        }

        override suspend fun <Value> mutate(
            signal: WorkspaceSignal,
            detail: String,
            operation: suspend () -> Value,
        ): WorkspaceMutationTransitionOutcome<Value> =
            WorkspaceMutationTransitionOutcome.Rejected(
                WorkspaceMutationTransitionFailure.ReconciliationRejected(
                    WorkspaceTransitionFailure.Closed,
                ),
            )
    }

    private object UnexpectedVerificationExecutor : AddDeclarationVerificationExecutor() {
        override suspend fun verify(
            command: AddDeclarationVerificationCommand,
        ): AddDeclarationVerificationResult = error("Transition rejection must precede verification")
    }
}
