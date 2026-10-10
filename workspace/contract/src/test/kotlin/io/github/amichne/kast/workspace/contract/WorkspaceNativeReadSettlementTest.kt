package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class WorkspaceNativeReadSettlementTest {
    private val identity = WorkspaceModelIdentity(
        (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/detached-project")) as Refinement.Refined).value,
        IdeReadHostLifetime.fromBoundary(UUID(0, 1)),
    )
    private val operation = WorkspaceReadOperationIdentity.Traced(UUID(0, 2))

    @Test
    fun `valid model does not prove settlement and pending observation retains its evidence`() {
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(1) }
        val ready = WorkspaceCapabilityReadiness.Ready(identity, (source.observe() as ProjectReadEpochObservation.Observed).epoch)
        val pending = assertInstanceOf(
            WorkspaceCapabilityReadiness.Pending::class.java,
            observeWorkspaceReadSettlement(ready, WorkspaceNativeReadSettlement.Running(listOf(operation))),
        )
        assertEquals(WorkspaceReadinessReason.NATIVE_WORK, pending.reason)
        assertEquals(WorkspaceReadinessNextAction.OBSERVE_SETTLEMENT, pending.nextAction)
        val detail = assertInstanceOf(WorkspaceReadinessDetail.UnsettledReads::class.java, pending.detail)
        assertSame(ready, detail.retainedObservation)
        assertEquals(listOf(operation), detail.operations)
        assertSame(ready, observeWorkspaceReadSettlement(ready, WorkspaceNativeReadSettlement.Quiescent))
    }

    @Test
    fun `retired or disposed incarnation remains terminal despite outstanding or settled operations`() {
        val disposed = WorkspaceCapabilityReadiness.Blocked(identity, WorkspaceReadinessReason.PROJECT_DISPOSED, WorkspaceReadinessNextAction.REOPEN_PROJECT)
        val retired = WorkspaceCapabilityReadiness.Blocked(identity, WorkspaceReadinessReason.RETIRED_INCARNATION, WorkspaceReadinessNextAction.ATTACH_HOST)
        val settlements = listOf(
            WorkspaceNativeReadSettlement.Quiescent,
            WorkspaceNativeReadSettlement.Running(listOf(operation)),
            WorkspaceNativeReadSettlement.Retired(listOf(operation)),
            WorkspaceNativeReadSettlement.Retired(emptyList()),
        )
        listOf(disposed, retired).forEach { readiness ->
            settlements.forEach { settlement ->
                val result = assertInstanceOf(WorkspaceCapabilityReadiness.Blocked::class.java, observeWorkspaceReadSettlement(readiness, settlement))
                assertEquals(readiness.reason, result.reason)
                assertEquals(readiness.nextAction, result.nextAction)
                assertEquals(identity, result.identity)
            }
        }
    }
}
