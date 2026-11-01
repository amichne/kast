package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

internal fun nativeUnsupportedBindingRead(
    source: ValueSite,
    scope: CompiledRelationScope,
    observation: IntellijReadObservation,
    obligation: ValueFlowObligation,
    work: () -> RelationWorkCount,
): ValueFlowRead =
    when (
        val step =
            ValueFlowStep.fromCompiler(
                source = source,
                transfers = emptyList(),
                obligations = listOf(obligation),
                terminal = ValueFlowTerminal.Unresolved,
                domain = scope.request,
                examinedWorkUnits = work(),
            )
    ) {
        is Refinement.Refined -> {
            observation.count(IntellijReadCounter.VALUE_FLOW_OBLIGATIONS)
            ValueFlowRead.Observed(step.value)
        }
        is Refinement.Rejected -> ValueFlowRead.ContractRejected(step.failure, work())
    }
