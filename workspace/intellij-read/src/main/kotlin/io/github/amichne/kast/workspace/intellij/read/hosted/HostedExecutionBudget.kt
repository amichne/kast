package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionBudgetCapacity
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit

enum class HostedBudgetProfile {
    SEMANTIC,
    SOURCE,
}

/** Protocol capacities enter explicitly; checkpoint storage remains operator-owned. */
data class HostedExecutionBudgetRequest(
    val requested: RequestedExecutionBudget = RequestedExecutionBudget(),
    val profile: HostedBudgetProfile = HostedBudgetProfile.SEMANTIC,
    val maximumResults: ResultLimit = ResultLimit.parse(Int.MAX_VALUE).proven(),
)

internal fun admitHostedExecutionBudget(
    limits: ReadLimits,
    request: HostedExecutionBudgetRequest,
    available: ElapsedTimeLimitMillis,
): AdmittedExecutionBudget {
    val resultParameter =
        when (request.profile) {
            HostedBudgetProfile.SEMANTIC -> ReadLimitParameter.SEMANTIC_RESULTS
            HostedBudgetProfile.SOURCE -> ReadLimitParameter.SOURCE_ENTITIES
        }
    val workParameter =
        when (request.profile) {
            HostedBudgetProfile.SEMANTIC -> ReadLimitParameter.SEMANTIC_WORK
            HostedBudgetProfile.SOURCE -> ReadLimitParameter.SOURCE_ENTITY_WORK
        }
    val bytesParameter =
        when (request.profile) {
            HostedBudgetProfile.SEMANTIC -> ReadLimitParameter.SEMANTIC_RETURNED_BYTES
            HostedBudgetProfile.SOURCE -> ReadLimitParameter.SOURCE_RETURNED_BYTES
        }
    return AdmittedExecutionBudget.admit(
        request.requested,
        ResourceBudget(
            ResultLimit.parse(limits[resultParameter].value).proven(),
            WorkUnitLimit.parse(limits[workParameter].value.toLong()).proven(),
            ElapsedTimeLimitMillis.parse(limits[ReadLimitParameter.SEMANTIC_MILLIS].value.toLong()).proven(),
        ),
        ReturnedByteLimit.parse(limits[bytesParameter].value.toLong()).proven(),
        ResourceBudget(
            ResultLimit.parse(limits[ReadLimitParameter.EXECUTION_MAX_RESULTS].value).proven(),
            WorkUnitLimit.parse(limits[ReadLimitParameter.EXECUTION_MAX_WORK].value.toLong()).proven(),
            ElapsedTimeLimitMillis.parse(limits[ReadLimitParameter.EXECUTION_MAX_MILLIS].value.toLong()).proven(),
        ),
        ReturnedByteLimit.parse(limits[ReadLimitParameter.EXECUTION_MAX_RETURNED_BYTES].value.toLong()).proven(),
        ExecutionBudgetCapacity(
            available,
            request.maximumResults,
            ReturnedByteLimit.parse(limits[ReadLimitParameter.HOST_RESPONSE_BYTES].value.toLong()).proven(),
        ),
    )
}

private fun <Value> Refinement<Value, *>.proven(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("An admitted positive read limit lost its proof")
    }
