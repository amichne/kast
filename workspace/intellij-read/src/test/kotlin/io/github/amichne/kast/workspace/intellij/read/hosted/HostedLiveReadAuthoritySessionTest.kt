package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmission
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.admitVfsPassiveReadObservation
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HostedLiveReadAuthoritySessionTest {
    @Test
    fun `epoch transition rejects both overlapping reads and fresh execution succeeds`() = runTest {
        val root = (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value
        var signal = 1
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(signal) }
        val oldEpoch = (source.observe() as ProjectReadEpochObservation.Observed).epoch
        val executor = HostedQueryExecutor(backgroundScope)
        val release = CompletableDeferred<Unit>()
        var evaluations = 0
        val reads =
            List(2) {
                async {
                    executor.execute(executor.endpoint) { progress ->
                        runHostedReadTransaction(progress, { freshness(root, oldEpoch, source.observe()) }) {
                            evaluations++
                            release.await()
                            42
                        }
                    }
                }
            }
        try {
            runCurrent()
            assertEquals(2, evaluations)
            assertTrue(reads.none { it.isCompleted })
            signal = 2
            release.complete(Unit)
            reads.forEach { read ->
                val result = read.await() as HostedExecution.Completed
                assertEquals(
                    HostedSemanticRead.Rejected(HostedQueryFailure.Freshness(VfsPassiveReadAdmissionFailure.Moved)),
                    result.value,
                )
                assertEquals(HostedQueryStage.CONTENT_REVALIDATION, result.stage)
            }
            assertEquals(2, evaluations, "Epoch movement must not re-evaluate either query")
            val currentEpoch = (source.observe() as ProjectReadEpochObservation.Observed).epoch
            val result =
                executor.execute(executor.endpoint) { progress ->
                    runHostedReadTransaction(progress, { freshness(root, currentEpoch, source.observe()) }) {
                        7
                    }
                } as HostedExecution.Completed
            assertEquals(HostedSemanticRead.Resolved(7), result.value)
        } finally {
            release.complete(Unit)
            executor.retire()
            executor.drain()
        }
    }

    @Test
    fun `delayed admission reobserves freshness and cannot move authority backwards`() {
        val root = (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value
        var signal = 1
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(signal) }
        fun epoch() = (source.observe() as ProjectReadEpochObservation.Observed).epoch
        val session = HostedLiveReadAuthoritySession(IdeReadHostLifetime.fromBoundary(UUID(0, 1)))
        val before = epoch()
        val first =
            (session.admit(root) { admitVfsPassiveReadObservation(root, before, source.observe()) }
                    as Refinement.Refined)
                .value
        signal = 2
        val after = epoch()
        val second =
            (session.admit(root) { admitVfsPassiveReadObservation(root, after, source.observe()) }
                    as Refinement.Refined)
                .value
        assertEquals(1L, first.reference.epoch.value)
        assertEquals(2L, second.reference.epoch.value)
        assertEquals(
            Refinement.Rejected(HostedQueryFailure.Freshness(VfsPassiveReadAdmissionFailure.Moved)),
            session.admit(root) { admitVfsPassiveReadObservation(root, before, source.observe()) },
        )
        assertSame(
            second,
            (session.admit(root) { admitVfsPassiveReadObservation(root, after, source.observe()) }
                    as Refinement.Refined)
                .value,
        )
        session.retire()
        assertEquals(
            Refinement.Rejected(HostedQueryFailure.RETIRED),
            session.admit(root) { error("Retired owner must not observe") },
        )
    }

    private fun freshness(
        root: CanonicalWorkspaceRoot,
        expected: ProjectReadEpoch<*>,
        observation: ProjectReadEpochObservation,
    ): Refinement<Unit, HostedQueryFailure> =
        when (val current = admitVfsPassiveReadObservation(root, expected, observation)) {
            is VfsPassiveReadAdmission.Admitted -> Refinement.Refined(Unit)
            is VfsPassiveReadAdmission.Rejected -> Refinement.Rejected(HostedQueryFailure.Freshness(current.failure))
        }
}
