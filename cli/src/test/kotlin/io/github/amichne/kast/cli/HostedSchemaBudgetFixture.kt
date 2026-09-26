package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionBudgetCapacity
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument

internal fun hostedSchemaBudgetGrant(request: ExecutionBudgetDocument): AdmittedExecutionBudget {
    val resources =
        ResourceBudget(
            ResultLimit.parse(128).refined(),
            WorkUnitLimit.parse(100).refined(),
            ElapsedTimeLimitMillis.parse(200).refined(),
        )
    val bytes = ReturnedByteLimit.parse(10_000).refined()
    return AdmittedExecutionBudget.admit(
        request.requested(),
        resources,
        bytes,
        resources,
        bytes,
        ExecutionBudgetCapacity(resources.elapsedTimeLimit, resources.resultLimit, bytes),
    )
}

private fun <Value> Refinement<Value, *>.refined(): Value = (this as Refinement.Refined).value
