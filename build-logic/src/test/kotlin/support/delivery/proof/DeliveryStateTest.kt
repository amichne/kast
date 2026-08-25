package support.delivery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class DeliveryStateNegativeTest {
    private val validated = KastVfsPassiveReusedIndexProgram.validated
    private val exactHead = AuthorityGitRevision("1".repeat(40))

    @Test fun `missing closure cannot derive terminal completion`() {
        val state = completeState(emptyList())

        assertInstanceOf(DerivedTaskState.Ready::class.java, state.taskStates.getValue(TaskId("KVP-001")))
        assertInstanceOf(DerivedTaskState.Blocked::class.java, state.taskStates.getValue(TaskId("KVP-002")))
        assertInstanceOf(DerivedTerminalState.Pending::class.java, state.terminal)
    }

    @Test fun `stale completion is invalid and cannot derive terminal completion`() {
        val stale = completionReceipt(TaskId("KVP-001"), AuthorityGitRevision("2".repeat(40)))
        val state = completeState(listOf(stale))

        assertEquals(
            DeliveryTaskInvalidation.STALE_EXACT_HEAD,
            assertInstanceOf(
                DerivedTaskState.Invalid::class.java,
                state.taskStates.getValue(TaskId("KVP-001")),
            ).invalidation,
        )
        assertInstanceOf(DerivedTerminalState.Pending::class.java, state.terminal)
    }

    @Test fun `duplicate completion is invalid and cannot derive terminal completion`() {
        val receipt = completionReceipt(TaskId("KVP-001"), exactHead)
        val state = completeState(listOf(receipt, receipt))

        assertEquals(
            DeliveryTaskInvalidation.DUPLICATE_COMPLETION_RECEIPT,
            assertInstanceOf(
                DerivedTaskState.Invalid::class.java,
                state.taskStates.getValue(TaskId("KVP-001")),
            ).invalidation,
        )
        assertInstanceOf(DerivedTerminalState.Pending::class.java, state.terminal)
    }

    @Test fun `gate receipt cannot enter completion fold`() {
        val gate = AdmittedProofReceipt(
            ProofReceiptId("KVP-001-RED-RECEIPT"),
            ProofReceiptDigest("3".repeat(64)),
            exactHead,
            TaskId("KVP-001"),
            ProofGateId("KVP-001-RED"),
        )
        assertEquals(
            DerivedProgramStateResult.Rejected(DeliveryStateFailure.NON_COMPLETION_RECEIPT),
            deriveProgramState(validated, exactHead, listOf(gate)),
        )
    }

    private fun completeState(receipts: List<AdmittedProofReceipt>) = assertInstanceOf(
        DerivedProgramStateResult.Complete::class.java,
        deriveProgramState(validated, exactHead, receipts),
    ).state
}

internal class DeliveryStateTest {
    private val validated = KastVfsPassiveReusedIndexProgram.validated
    private val exactHead = AuthorityGitRevision("1".repeat(40))

    @Test fun `partial closure derives proven ready blocked and pending states`() {
        val completed = validated.order.take(7).map { completionReceipt(it, exactHead) }
        val state = completeState(completed)

        assertEquals(7, state.taskStates.values.count { it is DerivedTaskState.Proven })
        assertInstanceOf(DerivedTaskState.Ready::class.java, state.taskStates.getValue(TaskId("KVP-008")))
        assertTrue(state.taskStates.values.any { it is DerivedTaskState.Blocked })
        assertTrue(state.requirementStates.values.any { it is DerivedRequirementState.Pending })
        assertInstanceOf(DerivedTerminalState.Pending::class.java, state.terminal)
    }

    @Test fun `complete exact-head closure derives terminal proof and all requirements`() {
        val completed = validated.order.map { completionReceipt(it, exactHead) }
        val state = completeState(completed)

        assertEquals(43, state.taskStates.values.count { it is DerivedTaskState.Proven })
        assertEquals(27, state.requirementStates.values.count {
            it is DerivedRequirementState.Passed
        })
        assertEquals(validated.program.terminalTask, state.criticalPath.last())
        assertTrue(state.criticalPath.zipWithNext().all { (before, after) ->
            before in validated.program.tasks.single { it.id == after }.dependencies.taskIds
        })
        val terminal = assertInstanceOf(DerivedTerminalState.Proven::class.java, state.terminal)
        assertEquals(exactHead, terminal.completion.exactHead)
        assertEquals(validated.program.terminalTask, terminal.completion.terminalReceipt.taskId)
    }

    private fun completeState(receipts: List<AdmittedProofReceipt>) = assertInstanceOf(
        DerivedProgramStateResult.Complete::class.java,
        deriveProgramState(validated, exactHead, receipts),
    ).state
}

private fun completionReceipt(
    taskId: TaskId,
    exactHead: AuthorityGitRevision,
) = AdmittedProofReceipt(
    ProofReceiptId("${taskId.value}-COMPLETE"),
    ProofReceiptDigest(sha256(taskId.value).value),
    exactHead,
    taskId,
    ProofGateId("${taskId.value}-COMPLETE-GATE"),
)
