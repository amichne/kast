package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class WorkspaceResourcePolicyTest {
    @Test
    fun `validated policy maps one exact limit to every expensive kind`() {
        val policy = policy(
            runtimeStarts = 1,
            imports = 2,
            transitions = 3,
            indexing = 4,
            longOperations = 5,
        )

        assertEquals(1, policy.limitFor(WorkspaceExpensiveWork.RUNTIME_START).value)
        assertEquals(2, policy.limitFor(WorkspaceExpensiveWork.PROJECT_IMPORT).value)
        assertEquals(3, policy.limitFor(WorkspaceExpensiveWork.WORKSPACE_TRANSITION).value)
        assertEquals(4, policy.limitFor(WorkspaceExpensiveWork.INDEXING).value)
        assertEquals(5, policy.limitFor(WorkspaceExpensiveWork.LONG_OPERATION).value)
        assertFalse(WorkspaceExpensiveWork.entries.any { it.name.contains("READ") })
    }

    @Test
    fun `resource observations preserve independent active states`() {
        val activity = WorkspaceResourceActivity(
            runtimeStarts = count(1),
            imports = count(2),
            transitions = count(3),
            indexing = count(4),
            longOperations = count(5),
        )
        assertEquals(1, activity.active(WorkspaceExpensiveWork.RUNTIME_START).value)
        assertEquals(2, activity.active(WorkspaceExpensiveWork.PROJECT_IMPORT).value)
        assertEquals(3, activity.active(WorkspaceExpensiveWork.WORKSPACE_TRANSITION).value)
        assertEquals(4, activity.active(WorkspaceExpensiveWork.INDEXING).value)
        assertEquals(5, activity.active(WorkspaceExpensiveWork.LONG_OPERATION).value)

        val observation = WorkspaceResourceObservation(
            heap = WorkspaceHeapUtilizationPercent.parse(89).refined(),
            edt = WorkspaceEdtLiveness.Live,
            activity = activity,
        )
        assertEquals(89, observation.heap.value)
        assertEquals(WorkspaceEdtLiveness.Live, observation.edt)
    }

    @Test
    fun `boundary parsers reject every invalid primitive`() {
        assertEquals(
            WorkspacePositiveLimitFailure.NOT_POSITIVE,
            WorkspaceConcurrencyLimit.parse(0).rejected(),
        )
        assertEquals(
            WorkspacePositiveLimitFailure.NOT_POSITIVE,
            WorkspaceQueueLimit.parse(-1).rejected(),
        )
        assertEquals(
            WorkspaceAdmissionWaitFailure.NOT_POSITIVE,
            WorkspaceAdmissionWaitMillis.parse(0).rejected(),
        )
        assertEquals(
            WorkspaceAdmissionWaitFailure.TOO_LARGE,
            WorkspaceAdmissionWaitMillis.parse(Long.MAX_VALUE).rejected(),
        )
        assertEquals(
            WorkspacePercentageFailure.OUT_OF_RANGE,
            WorkspaceCriticalHeapPercent.parse(0).rejected(),
        )
        assertEquals(
            WorkspacePercentageFailure.OUT_OF_RANGE,
            WorkspaceHeapUtilizationPercent.parse(101).rejected(),
        )
        assertEquals(
            WorkspaceResourceCountFailure.NEGATIVE,
            WorkspaceResourceCount.parse(-1).rejected(),
        )
    }

    private fun policy(
        runtimeStarts: Int,
        imports: Int,
        transitions: Int,
        indexing: Int,
        longOperations: Int,
    ): WorkspaceResourcePolicy = WorkspaceResourcePolicy(
        runtimeStarts = WorkspaceConcurrencyLimit.parse(runtimeStarts).refined(),
        imports = WorkspaceConcurrencyLimit.parse(imports).refined(),
        transitions = WorkspaceConcurrencyLimit.parse(transitions).refined(),
        indexing = WorkspaceConcurrencyLimit.parse(indexing).refined(),
        longOperations = WorkspaceConcurrencyLimit.parse(longOperations).refined(),
        queuedWaiters = WorkspaceQueueLimit.parse(8).refined(),
        waitTimeout = WorkspaceAdmissionWaitMillis.parse(1_000L).refined(),
        criticalHeap = WorkspaceCriticalHeapPercent.parse(90).refined(),
    )

    private fun count(value: Int): WorkspaceResourceCount =
        WorkspaceResourceCount.parse(value).refined()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.rejected(): Failure = when (this) {
        is Refinement.Refined -> error(value.toString())
        is Refinement.Rejected -> failure
    }
}
