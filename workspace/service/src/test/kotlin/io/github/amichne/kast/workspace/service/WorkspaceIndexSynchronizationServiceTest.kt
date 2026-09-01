package io.github.amichne.kast.workspace.service

import io.github.amichne.kast.kernel.EvidenceGeneration
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class WorkspaceIndexSynchronizationServiceTest {
    @Test
    fun `ready refresh publishes an advanced workspace`() {
        val prior = workspace(1, "state-one")
        val next = workspace(2, "state-two")
        var refreshed: PublishedWorkspace? = null
        val service = WorkspaceIndexSynchronizationService(
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
        val service = WorkspaceIndexSynchronizationService(
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
                IndexSynchronizationFailure.Refresh(WorkspaceIndexRefreshFailure.INDEXING_TIMED_OUT),
            ),
            service.synchronize(),
        )
        assertEquals(0, publications.get())
    }

    @Test
    fun `unready workspace cannot refresh`() {
        val refreshes = AtomicInteger()
        val service = WorkspaceIndexSynchronizationService(
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

    private fun workspace(generation: Long, state: String): PublishedWorkspace {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val reconciled = ReconciledWorkspace.admit(
            WorkspaceCandidate(root, WorkspaceStateIdentity.parse(state).refined()),
            WorkspaceEvidenceKind.entries.toSet(),
        ).refined()
        return PublishedWorkspace.publish(reconciled, EvidenceGeneration.parse(generation).refined())
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        (this as Refinement.Refined).value
}
