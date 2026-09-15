package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Source-only field evidence; nullable budget controls preserve the shared default-selection contract. */
internal fun validateSourceExecutionBudget(root: JsonObject) {
    val element = root["execution_budget"] ?: return
    if (element == JsonNull) return
    val budget = sourceObjectAt(element, SourceRequestPath.EXECUTION_BUDGET)
    sourceFields(
        budget,
        SourceRequestPath.EXECUTION_BUDGET,
        setOf("max_elapsed_ms", "max_work_units", "max_results", "max_returned_bytes"),
    )
    sourceBudgetNumber(budget, "max_elapsed_ms", SourceRequestPath.BUDGET_ELAPSED)
    sourceBudgetNumber(budget, "max_work_units", SourceRequestPath.BUDGET_WORK)
    sourceBudgetNumber(budget, "max_results", SourceRequestPath.BUDGET_RESULTS, Int.MAX_VALUE.toLong())
    sourceBudgetNumber(budget, "max_returned_bytes", SourceRequestPath.BUDGET_BYTES)
}

private fun sourceBudgetNumber(
    budget: JsonObject,
    key: String,
    path: SourceRequestPath,
    maximum: Long = Long.MAX_VALUE,
) {
    val value = budget[key] ?: return
    if (value == JsonNull) return
    sourceNumber(budget, key, path, 1, maximum, SourceRequestRule.EXECUTION_BUDGET)
}
