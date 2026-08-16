package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission
import io.github.amichne.kast.idea.testPublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRootFailure
import io.github.amichne.kast.workspace.spi.SemanticReadExecution
import io.github.amichne.kast.workspace.spi.SemanticReadExecutor
import io.github.amichne.kast.workspace.spi.SemanticReadAdmissionFailure
import io.github.amichne.kast.workspace.spi.SemanticReadLeaseFailure
import io.github.amichne.kast.workspace.spi.SemanticReadFreshness
import io.github.amichne.kast.workspace.spi.SemanticReadFreshnessAuthority
import io.github.amichne.kast.workspace.spi.SemanticReadFreshnessRequirement
import io.github.amichne.kast.workspace.spi.RuntimeLivenessAdmission
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
            executor(legacy),
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
            val executor = executor(
                legacy = legacy,
                workspaceRootPath = { Path.of("relative/workspace") },
            )

            val result = executor.current { "must-not-run" }

            assertEquals(
                SemanticReadExecution.Rejected(
                    SemanticReadAdmissionFailure.SemanticUnavailable(
                        SemanticReadLeaseFailure.WorkspaceRootUnrepresentable(
                            CanonicalWorkspaceRootFailure.NOT_ABSOLUTE,
                        ),
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
        val executor = executor(legacy, root::get)

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
        val executor = executor(legacy, root::get)

        val result = executor.current {
            root.set(Path.of("/workspace/moved"))
            "must-not-return"
        }

        assertEquals(
            SemanticReadExecution.Rejected(
                SemanticReadAdmissionFailure.SemanticUnavailable(
                    SemanticReadLeaseFailure.WorkspaceRootMoved(
                        expected = canonicalRoot("/workspace/root"),
                        observed = canonicalRoot("/workspace/moved"),
                    ),
                ),
            ),
            result,
        )
        assertTrue(legacy.released)
    }

    @Test
    fun `adapter rejects generation movement before returning operation result`() = runBlocking {
        val legacy = MutableLegacyAuthority(generation(7))
        val executor = executor(legacy)

        val result = executor.current {
            legacy.published = generation(8)
            "must-not-return"
        }

        assertEquals(7, result.rejectedFailure().expectedGeneration().value)
        assertEquals(8, result.rejectedFailure().observedGeneration().value)
        assertTrue(legacy.released)
    }

    @Test
    fun `freshness states reject with distinct blockers before a lease opens`() = runBlocking {
        val cases = listOf(
            SemanticReadFreshness.DumbMode to SemanticReadLeaseFailure.DumbMode,
            SemanticReadFreshness.TransitionInProgress to SemanticReadLeaseFailure.TransitionInProgress,
            SemanticReadFreshness.WorkspaceBlocked to SemanticReadLeaseFailure.WorkspaceBlocked,
        )

        cases.forEach { (freshness, expected) ->
            val legacy = MutableLegacyAuthority(generation(7))
            val result = executor(
                legacy = legacy,
                freshness = SemanticReadFreshnessAuthority { freshness },
            ).current { "must-not-run" }

            assertEquals(
                SemanticReadExecution.Rejected(
                    SemanticReadAdmissionFailure.SemanticUnavailable(expected),
                ),
                result,
            )
            assertEquals(0, legacy.openAttempts)
        }
    }

    @Test
    fun `qualified dumb-mode operation retains the lease while strict reads reject`() = runBlocking {
        val legacy = MutableLegacyAuthority(generation(7))
        val executor = executor(
            legacy = legacy,
            freshness = SemanticReadFreshnessAuthority { SemanticReadFreshness.DumbMode },
        )

        val result = executor.current(
            freshness = SemanticReadFreshnessRequirement.QUALIFIED_DUMB_MODE,
        ) { lease ->
            "qualified@${lease.generation.value}"
        }

        assertEquals("qualified@7", result.completedPayload())
        assertEquals(1, legacy.openAttempts)
        assertTrue(legacy.released)
    }

    private class MutableLegacyAuthority(
        var published: PublishedWorkspaceGeneration,
    ) : WorkspaceSemanticReadAuthority {
        var released: Boolean = false
        var openAttempts: Int = 0

        override fun status(): IdeaIndexSemanticAdmission.Status =
            IdeaIndexSemanticAdmission.Status.Ready(published)

        override fun openRead(): IdeaIndexSemanticAdmission.WorkspaceReadToken =
            IdeaIndexSemanticAdmission.WorkspaceReadToken(
                revision = 1,
                generation = published,
                release = { released = true },
            ).also { openAttempts += 1 }

        override fun isReadCurrent(
            token: IdeaIndexSemanticAdmission.WorkspaceReadToken,
        ): Boolean = token.generation == published

        override fun isReconciliationCurrent(
            token: IdeaIndexSemanticAdmission.ReconciliationToken,
        ): Boolean = true
    }

    private fun executor(
        legacy: MutableLegacyAuthority,
        workspaceRootPath: () -> Path = { Path.of("/workspace/root") },
        freshness: SemanticReadFreshnessAuthority = IdeaSemanticReadFreshnessAuthority(
            dumbMode = { IdeaDumbModeObservation.Smart },
            semanticStatus = legacy::status,
        ),
    ): SemanticReadExecutor = SemanticReadExecutor(
        runtimeLiveness = { RuntimeLivenessAdmission.Live },
        authority = ExistingSemanticReadLeaseAuthority(
            delegate = legacy,
            workspaceRootPath = workspaceRootPath,
            freshness = freshness,
        ),
    )

    private fun generation(value: Long): PublishedWorkspaceGeneration =
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

    private fun <Payload> SemanticReadExecution<Payload>.completedPayload(): Payload = when (this) {
        is SemanticReadExecution.Completed -> payload
        is SemanticReadExecution.Rejected -> error("Expected completion, got $failure")
    }

    private fun <Payload> SemanticReadExecution<Payload>.rejectedFailure(): SemanticReadLeaseFailure = when (this) {
        is SemanticReadExecution.Completed -> error("Expected rejection, got $payload")
        is SemanticReadExecution.Rejected -> when (val rejected = failure) {
            is SemanticReadAdmissionFailure.RuntimeUnavailable ->
                error("Expected semantic rejection, got ${rejected.failure}")
            is SemanticReadAdmissionFailure.SemanticUnavailable -> rejected.failure
        }
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
