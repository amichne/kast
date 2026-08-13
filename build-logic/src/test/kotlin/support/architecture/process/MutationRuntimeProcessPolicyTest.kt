package support.architecture.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class MutationRuntimeProcessPolicyTest {
    private val processes = KastMutationRuntimeProcesses.all.associateBy(MutationRuntimeProcessPolicy::id)

    @Test
    fun `apply branch rejoins after only the selected lane`() {
        val semantic = assertInstanceOf<MutationRuntimeAdmission.ApplyLane>(
            processes.getValue(MutationRuntimeProcessId.RP11S).admission,
        )
        val external = assertInstanceOf<MutationRuntimeAdmission.ApplyLane>(
            processes.getValue(MutationRuntimeProcessId.RP11E).admission,
        )
        val join = assertInstanceOf<MutationRuntimeAdmission.SelectedApplyLaneJoin>(
            processes.getValue(MutationRuntimeProcessId.RP12).admission,
        )

        assertEquals(MutationApplyLane.SEMANTIC, semantic.lane)
        assertEquals(MutationApplyLane.EXTERNAL, external.lane)
        assertEquals(setOf(MutationRuntimeProcessId.RP10), semantic.orderingDependencies)
        assertEquals(setOf(MutationRuntimeProcessId.RP10), external.orderingDependencies)
        assertEquals(MutationApplyLane.entries.toSet(), join.lanes)
        assertEquals(
            setOf(MutationRuntimeProcessId.RP11S, MutationRuntimeProcessId.RP11E),
            join.orderingDependencies,
        )
    }

    @Test
    fun `recovery is an interrupt after durable preparation`() {
        val recovery = assertInstanceOf<MutationRuntimeAdmission.RecoveryInterruptAfterPreparation>(
            processes.getValue(MutationRuntimeProcessId.RP19).admission,
        )

        assertEquals(MutationRuntimeProcessId.RP09, recovery.preparedBy)
        assertEquals(setOf(MutationRuntimeProcessId.RP09), recovery.orderingDependencies)
    }
}
