package io.github.amichne.kast.workspace.service

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.kernel.KastWorkspaceReadinessOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IndexSynchronizationFailure
import io.github.amichne.kast.workspace.contract.IndexSynchronizationResult
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefresh
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshFailure
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshOperations
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspacePublicationBlocker
import io.github.amichne.kast.workspace.contract.WorkspacePublicationRun
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkspaceIndexSynchronizationServiceTest {
    @Test
    fun `unready lifecycle phases remain distinguishable without triggering refresh`() {
        val states =
            mapOf(
                WorkspaceRuntimeState.Absent to KastWorkspaceReadinessOutcome.WORKSPACE_ABSENT,
                WorkspaceRuntimeState.Starting to KastWorkspaceReadinessOutcome.WORKSPACE_STARTING,
                WorkspaceRuntimeState.Stopping to KastWorkspaceReadinessOutcome.WORKSPACE_STOPPING,
                WorkspaceRuntimeState.Reconciling to KastWorkspaceReadinessOutcome.REFRESH_BASIS_UNAVAILABLE,
            )
        for ((state, expected) in states) {
            val observations = mutableListOf<KastWorkspaceReadinessOutcome>()
            val service =
                WorkspaceIndexSynchronizationService(
                    WorkspaceInspectionOperations { state },
                    WorkspaceIndexRefreshOperations { error("unready workspace must not refresh") },
                    WorkspaceIndexPublicationOperations { error("unready workspace must not publish") },
                    observability = readinessObservations(observations),
                )
            assertEquals(
                IndexSynchronizationResult.Rejected(IndexSynchronizationFailure.WorkspaceNotReady),
                service.ready(),
            )
            assertEquals(listOf(expected), observations)
        }
    }

    @Test
    fun `unavailable source observation emits its finite reason without changing rejection`() {
        val prior = workspace(1, "before")
        val observations = mutableListOf<KastWorkspaceReadinessOutcome>()
        val service =
            WorkspaceIndexSynchronizationService(
                WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(prior) },
                WorkspaceIndexRefreshOperations { error("source failure must not refresh") },
                WorkspaceIndexPublicationOperations { error("source failure must not publish") },
                observability = readinessObservations(observations),
            )
        assertEquals(
            IndexSynchronizationResult.Rejected(
                IndexSynchronizationFailure.PublicationBlocked(WorkspacePublicationBlocker.CandidateCaptureUnavailable)
            ),
            service.ready(),
        )
        assertEquals(listOf(KastWorkspaceReadinessOutcome.SOURCE_OBSERVATION_UNAVAILABLE), observations)
    }

    @Test
    fun `physical refresh failure emits its exact finite classification`() {
        val classifications =
            mapOf(
                WorkspaceIndexRefreshFailure.INVALID_SOURCE_ROOT_SCOPE to
                    KastWorkspaceReadinessOutcome.REFRESH_INVALID_SOURCE_ROOT_SCOPE,
                WorkspaceIndexRefreshFailure.REFRESH_UNAVAILABLE to KastWorkspaceReadinessOutcome.REFRESH_UNAVAILABLE,
                WorkspaceIndexRefreshFailure.INDEXING_INTERRUPTED to KastWorkspaceReadinessOutcome.INDEXING_INTERRUPTED,
                WorkspaceIndexRefreshFailure.INDEXING_TIMED_OUT to KastWorkspaceReadinessOutcome.INDEXING_TIMED_OUT,
                WorkspaceIndexRefreshFailure.INDEXING_FAILED to KastWorkspaceReadinessOutcome.INDEXING_FAILED,
            )
        val prior = workspace(1, "before")
        for ((failure, expected) in classifications) {
            val observations = mutableListOf<KastWorkspaceReadinessOutcome>()
            val service =
                WorkspaceIndexSynchronizationService(
                    WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(prior) },
                    WorkspaceIndexRefreshOperations { WorkspaceIndexRefresh.Rejected(failure) },
                    WorkspaceIndexPublicationOperations { error("refresh rejection must not publish") },
                    observability = readinessObservations(observations),
                )
            assertEquals(
                IndexSynchronizationResult.Rejected(IndexSynchronizationFailure.Refresh(failure)),
                service.synchronize(),
            )
            assertEquals(listOf(expected), observations)
        }
    }

    @Test
    fun `publication rejection preserves the exact blocker classification`() {
        val blockers =
            mapOf(
                WorkspacePublicationBlocker.ModelInputsChanged to KastWorkspaceReadinessOutcome.MODEL_INPUTS_CHANGED,
                WorkspacePublicationBlocker.ModelInputsUnavailable to
                    KastWorkspaceReadinessOutcome.MODEL_INPUTS_UNAVAILABLE,
                WorkspacePublicationBlocker.CandidateCaptureUnavailable to
                    KastWorkspaceReadinessOutcome.CANDIDATE_CAPTURE_UNAVAILABLE,
                WorkspacePublicationBlocker.ReconciliationUnavailable to
                    KastWorkspaceReadinessOutcome.RECONCILIATION_UNAVAILABLE,
                WorkspacePublicationBlocker.PublicationUnavailable to
                    KastWorkspaceReadinessOutcome.PUBLICATION_UNAVAILABLE,
            )
        val prior = workspace(1, "before")
        for ((blocker, expected) in blockers) {
            val observations = mutableListOf<KastWorkspaceReadinessOutcome>()
            val service =
                WorkspaceIndexSynchronizationService(
                    WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(prior) },
                    WorkspaceIndexRefreshOperations { WorkspaceIndexRefresh.Refreshed },
                    WorkspaceIndexPublicationOperations { WorkspacePublicationRun.Blocked(blocker) },
                    observability = readinessObservations(observations),
                )
            assertEquals(
                IndexSynchronizationResult.Rejected(IndexSynchronizationFailure.PublicationBlocked(blocker)),
                service.synchronize(),
            )
            assertEquals(listOf(expected), observations)
        }
    }

    @Test
    fun `ready refresh publishes an advanced workspace`() {
        val prior = workspace(1, "state-one")
        val next = workspace(2, "state-two")
        var refreshed: PublishedWorkspace? = null
        val service =
            WorkspaceIndexSynchronizationService(
                WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(prior) },
                WorkspaceIndexRefreshOperations { workspace ->
                    refreshed = workspace
                    WorkspaceIndexRefresh.Refreshed
                },
                WorkspaceIndexPublicationOperations { WorkspacePublicationRun.Published(next) },
            )

        assertEquals(IndexSynchronizationResult.Synchronized(next), service.synchronize())
        assertEquals(prior, refreshed)
    }

    @Test
    fun `refresh rejection cannot reach publication`() {
        val prior = workspace(1, "state-one")
        val publications = AtomicInteger()
        val service =
            WorkspaceIndexSynchronizationService(
                WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(prior) },
                WorkspaceIndexRefreshOperations {
                    WorkspaceIndexRefresh.Rejected(WorkspaceIndexRefreshFailure.INDEXING_TIMED_OUT)
                },
                WorkspaceIndexPublicationOperations {
                    publications.incrementAndGet()
                    WorkspacePublicationRun.Unchanged(prior)
                },
            )

        assertEquals(
            IndexSynchronizationResult.Rejected(
                IndexSynchronizationFailure.Refresh(WorkspaceIndexRefreshFailure.INDEXING_TIMED_OUT)
            ),
            service.synchronize(),
        )
        assertEquals(0, publications.get())
    }

    @Test
    fun `unready workspace cannot refresh`() {
        val refreshes = AtomicInteger()
        val service =
            WorkspaceIndexSynchronizationService(
                WorkspaceInspectionOperations { WorkspaceRuntimeState.Reconciling },
                WorkspaceIndexRefreshOperations {
                    refreshes.incrementAndGet()
                    WorkspaceIndexRefresh.Refreshed
                },
                WorkspaceIndexPublicationOperations {
                    WorkspacePublicationRun.Blocked(WorkspacePublicationBlocker.PublicationUnavailable)
                },
            )

        assertEquals(
            IndexSynchronizationResult.Rejected(IndexSynchronizationFailure.WorkspaceNotReady),
            service.synchronize(),
        )
        assertEquals(0, refreshes.get())
    }

    @Test
    fun `unchanged physical identity reuses publication without refresh`() {
        val prior = workspace(1, "same")
        var refreshes = 0
        val observations = mutableListOf<KastWorkspaceReadinessOutcome>()
        val service =
            WorkspaceIndexSynchronizationService(
                WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(prior) },
                WorkspaceIndexRefreshOperations {
                    refreshes++
                    WorkspaceIndexRefresh.Refreshed
                },
                WorkspaceIndexPublicationOperations { WorkspacePublicationRun.Unchanged(prior) },
                io.github.amichne.kast.workspace.contract.WorkspaceSourceObservationOperations {
                    io.github.amichne.kast.workspace.contract.WorkspaceSourceObservation.Observed(prior.sourceState)
                },
                observability = readinessObservations(observations),
            )
        repeat(2) { assertEquals(IndexSynchronizationResult.Unchanged(prior), service.ready()) }
        assertEquals(0, refreshes)
        assertEquals(listOf(KastWorkspaceReadinessOutcome.REUSED, KastWorkspaceReadinessOutcome.REUSED), observations)
    }

    @Test
    fun `concurrent changed observations converge on one successor and retain prior proof`() {
        val prior = workspace(1, "before")
        val next = workspace(2, "after")
        val current = java.util.concurrent.atomic.AtomicReference(prior)
        val refreshes = AtomicInteger()
        val publications = AtomicInteger()
        val service =
            WorkspaceIndexSynchronizationService(
                WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(current.get()) },
                WorkspaceIndexRefreshOperations {
                    refreshes.incrementAndGet()
                    WorkspaceIndexRefresh.Refreshed
                },
                WorkspaceIndexPublicationOperations { expected ->
                    assertEquals(prior, expected)
                    publications.incrementAndGet()
                    current.set(next)
                    WorkspacePublicationRun.Published(next)
                },
                io.github.amichne.kast.workspace.contract.WorkspaceSourceObservationOperations {
                    io.github.amichne.kast.workspace.contract.WorkspaceSourceObservation.Observed(next.sourceState)
                },
            )
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val calls =
                (1..2).map {
                    pool.submit<IndexSynchronizationResult> {
                        start.await()
                        service.ready()
                    }
                }
            start.countDown()
            val results = calls.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is IndexSynchronizationResult.Synchronized })
            assertEquals(1, results.count { it is IndexSynchronizationResult.Unchanged })
            assertEquals(1, refreshes.get())
            assertEquals(1, publications.get())
            assertEquals(1L, prior.readLease.generation.value)
            assertEquals(2L, current.get().readLease.generation.value)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `unavailable source observation cannot reuse prior readiness`() {
        val prior = workspace(1, "before")
        val service =
            WorkspaceIndexSynchronizationService(
                WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(prior) },
                WorkspaceIndexRefreshOperations { error("unknown source must fail closed") },
                WorkspaceIndexPublicationOperations { error("unknown source must not publish") },
            )
        assertEquals(
            IndexSynchronizationResult.Rejected(
                IndexSynchronizationFailure.PublicationBlocked(WorkspacePublicationBlocker.CandidateCaptureUnavailable)
            ),
            service.ready(),
        )
    }

    private fun readinessObservations(values: MutableList<KastWorkspaceReadinessOutcome>): KastObservability =
        object : KastObservability by KastObservability.Disabled {
            override fun observeWorkspaceReadiness(outcome: KastWorkspaceReadinessOutcome) {
                values.add(outcome)
            }
        }

    private fun workspace(generation: Long, state: String): PublishedWorkspace {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val reconciled =
            ReconciledWorkspace.admit(
                    WorkspaceCandidate(root, WorkspaceStateIdentity.parse(state).refined()),
                    WorkspaceEvidenceKind.entries.toSet(),
                )
                .refined()
        return PublishedWorkspace.publish(reconciled, EvidenceGeneration.parse(generation).refined())
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = (this as Refinement.Refined).value
}
