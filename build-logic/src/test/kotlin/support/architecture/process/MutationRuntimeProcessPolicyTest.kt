package support.architecture.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import support.architecture.ArchitecturePolicyFailure
import support.architecture.ArchitecturePolicyValidation
import support.architecture.ArchitecturePolicyValidator
import support.architecture.KastArchitecturePolicy

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
        assertEquals(KastMutationRuntimeProcesses.postRecoveryPreparationFailurePoints, recovery.failurePoints)
        assertEquals(MutationRecoveryTerminal.entries.toSet(), recovery.terminalOutcomes)
    }

    @Test
    fun `all-lanes join is rejected as invalid runtime topology`() {
        val invalid = KastMutationRuntimeProcesses.all.map { process ->
            if (process.id == MutationRuntimeProcessId.RP12) {
                process.copy(admission = MutationRuntimeAdmission.AllApplyLanesJoin)
            } else {
                process
            }
        }

        val validation = assertInstanceOf<MutationRuntimeTopologyValidation.Invalid>(
            MutationRuntimeTopologyValidator.validate(invalid),
        )

        assertTrue(
            MutationRuntimeTopologyFailure.SELECTED_APPLY_LANE_JOIN_REQUIRED in validation.failures,
        )
    }

    @Test
    fun `invalid runtime topology becomes closed architecture policy failure data`() {
        val definition = KastArchitecturePolicy.definition()
        val invalidProcesses = definition.mutationRuntimeProcesses.map { process ->
            if (process.id == MutationRuntimeProcessId.RP12) {
                process.copy(admission = MutationRuntimeAdmission.AllApplyLanesJoin)
            } else {
                process
            }
        }

        val validation = assertInstanceOf<ArchitecturePolicyValidation.Invalid>(
            ArchitecturePolicyValidator.validate(
                definition.copy(mutationRuntimeProcesses = invalidProcesses),
            ),
        )

        assertTrue(
            ArchitecturePolicyFailure.InvalidMutationRuntimeTopology(
                MutationRuntimeTopologyFailure.SELECTED_APPLY_LANE_JOIN_REQUIRED,
            ) in validation.failures,
        )
    }

    @Test
    fun `mismatched lane and incomplete recovery policy fail closed`() {
        val invalid = KastMutationRuntimeProcesses.all.map { process ->
            when (process.id) {
                MutationRuntimeProcessId.RP11E -> process.copy(
                    admission = MutationRuntimeAdmission.ApplyLane(MutationApplyLane.SEMANTIC),
                )
                MutationRuntimeProcessId.RP19 -> process.copy(
                    admission = MutationRuntimeAdmission.RecoveryInterruptAfterPreparation(
                        preparedBy = MutationRuntimeProcessId.RP10,
                        failurePoints = setOf(MutationRuntimeProcessId.RP11S),
                        terminalOutcomes = setOf(MutationRecoveryTerminal.ROLLED_BACK),
                    ),
                )
                else -> process
            }
        }

        val validation = assertInstanceOf<MutationRuntimeTopologyValidation.Invalid>(
            MutationRuntimeTopologyValidator.validate(invalid),
        )

        assertEquals(
            setOf(
                MutationRuntimeTopologyFailure.EXTERNAL_APPLY_LANE_INVALID,
                MutationRuntimeTopologyFailure.RECOVERY_PREPARATION_POINT_INVALID,
                MutationRuntimeTopologyFailure.POST_PREPARATION_RECOVERY_COVERAGE_INVALID,
                MutationRuntimeTopologyFailure.RECOVERY_TERMINALS_INVALID,
            ),
            validation.failures,
        )
    }
}
