package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission
import io.github.amichne.kast.idea.testPublishedWorkspaceGeneration
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.spi.SemanticReadExecution
import io.github.amichne.kast.workspace.spi.SemanticReadExecutor
import io.github.amichne.kast.workspace.spi.SemanticReadLeaseFailure
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

class SemanticReadLeaseAdmissionTest {
    @Test
    fun `adapter returns detached result with canonical root and published generation`() = runBlocking {
        val legacy = MutableLegacyAuthority(generation(7))
        val root = AtomicReference(canonicalRoot("/workspace/root"))
        val executor = SemanticReadExecutor(
            ExistingSemanticReadLeaseAuthority(legacy, root::get),
        )

        val result = executor.current { lease ->
            lease.workspaceRoot.value + "@" + lease.generation.value
        }

        assertEquals(
            SemanticReadExecution.Completed(
                lease = result.completedLease(),
                payload = "/workspace/root@7",
            ),
            result,
        )
        assertEquals(canonicalRoot("/workspace/root"), result.completedLease().workspaceRoot)
        assertEquals(7, result.completedLease().generation.value)
        assertTrue(legacy.released)
    }

    @Test
    fun `adapter rejects root movement before returning operation result`() = runBlocking {
        val legacy = MutableLegacyAuthority(generation(7))
        val root = AtomicReference(canonicalRoot("/workspace/root"))
        val executor = SemanticReadExecutor(
            ExistingSemanticReadLeaseAuthority(legacy, root::get),
        )

        val result = executor.current {
            root.set(canonicalRoot("/workspace/moved"))
            "must-not-return"
        }

        assertEquals(
            SemanticReadExecution.Rejected(
                SemanticReadLeaseFailure.WorkspaceRootMoved(
                    expected = canonicalRoot("/workspace/root"),
                    observed = canonicalRoot("/workspace/moved"),
                ),
            ),
            result,
        )
        assertTrue(legacy.released)
    }

    @Test
    fun `adapter rejects generation movement before returning operation result`() = runBlocking {
        val legacy = MutableLegacyAuthority(generation(7))
        val executor = SemanticReadExecutor(
            ExistingSemanticReadLeaseAuthority(
                legacy,
                canonicalWorkspaceRoot = { canonicalRoot("/workspace/root") },
            ),
        )

        val result = executor.current {
            legacy.published = generation(8)
            "must-not-return"
        }

        assertEquals(7, result.rejectedFailure().expectedGeneration().value)
        assertEquals(8, result.rejectedFailure().observedGeneration().value)
        assertTrue(legacy.released)
    }

    private class MutableLegacyAuthority(
        var published: PublishedWorkspaceGenerationManifest,
    ) : WorkspaceSemanticReadAuthority {
        var released: Boolean = false

        override fun status(): IdeaIndexSemanticAdmission.Status =
            IdeaIndexSemanticAdmission.Status.Ready(published)

        override fun openRead(): IdeaIndexSemanticAdmission.WorkspaceReadToken =
            IdeaIndexSemanticAdmission.WorkspaceReadToken(
                revision = 1,
                generation = published,
                release = { released = true },
            )

        override fun isReadCurrent(
            token: IdeaIndexSemanticAdmission.WorkspaceReadToken,
        ): Boolean = token.generation == published

        override fun isReconciliationCurrent(
            token: IdeaIndexSemanticAdmission.ReconciliationToken,
        ): Boolean = true
    }

    private fun generation(value: Long): PublishedWorkspaceGenerationManifest =
        testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(value))

    private fun canonicalRoot(value: String): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(value)).refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun <Payload> SemanticReadExecution<Payload>.completedLease() = when (this) {
        is SemanticReadExecution.Completed -> lease
        is SemanticReadExecution.Rejected -> error("Expected completion, got $failure")
    }

    private fun <Payload> SemanticReadExecution<Payload>.rejectedFailure() = when (this) {
        is SemanticReadExecution.Completed -> error("Expected rejection, got $payload")
        is SemanticReadExecution.Rejected -> failure
    }

    private fun SemanticReadLeaseFailure.expectedGeneration() = when (this) {
        is SemanticReadLeaseFailure.PublishedGenerationMoved -> expected
        else -> error("Expected generation movement, got $this")
    }

    private fun SemanticReadLeaseFailure.observedGeneration() = when (this) {
        is SemanticReadLeaseFailure.PublishedGenerationMoved -> observed
        else -> error("Expected generation movement, got $this")
    }
}
