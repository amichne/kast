package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission
import io.github.amichne.kast.idea.testPublishedWorkspaceGeneration
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRootFailure
import io.github.amichne.kast.workspace.spi.SemanticReadExecution
import io.github.amichne.kast.workspace.spi.SemanticReadExecutor
import io.github.amichne.kast.workspace.spi.SemanticReadLeaseFailure
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

class SemanticReadLeaseAdmissionTest {
    @Test
    fun `production gate rejects a payload computed across publication generations`() {
        val legacy = MutableLegacyAuthority(generation(7))
        val gate = WorkspaceSemanticGate(
            SemanticReadExecutor(
                ExistingSemanticReadLeaseAuthority(
                    legacy,
                    workspaceRootPath = { Path.of("/workspace/root") },
                ),
            ),
        )

        val failure = assertThrows<ConflictException> {
            runBlocking {
                gate.current<String> {
                    legacy.published = generation(8)
                    "must-not-return"
                }
            }
        }

        assertEquals(
            "Workspace moved during the semantic operation; retry against the next READY generation",
            failure.message,
        )
        assertTrue(legacy.released)
    }

    @Test
    fun `adapter rejects an unrepresentable physical workspace root before opening a read`() =
        runBlocking {
            val legacy = MutableLegacyAuthority(generation(7))
            val executor = SemanticReadExecutor(
                ExistingSemanticReadLeaseAuthority(
                    legacy,
                    workspaceRootPath = { Path.of("relative/workspace") },
                ),
            )

            val result = executor.current { "must-not-run" }

            assertEquals(
                SemanticReadExecution.Rejected(
                    SemanticReadLeaseFailure.WorkspaceRootUnrepresentable(
                        CanonicalWorkspaceRootFailure.NOT_ABSOLUTE,
                    ),
                ),
                result,
            )
            assertEquals(false, legacy.released)
        }

    @Test
    fun `adapter returns detached result with canonical root and published generation`() = runBlocking {
        val legacy = MutableLegacyAuthority(generation(7))
        val root = AtomicReference(Path.of("/workspace/root"))
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
        val root = AtomicReference(Path.of("/workspace/root"))
        val executor = SemanticReadExecutor(
            ExistingSemanticReadLeaseAuthority(legacy, root::get),
        )

        val result = executor.current {
            root.set(Path.of("/workspace/moved"))
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
                workspaceRootPath = { Path.of("/workspace/root") },
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
