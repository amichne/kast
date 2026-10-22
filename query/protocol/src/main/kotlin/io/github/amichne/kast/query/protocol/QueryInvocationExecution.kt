package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryWorkCount
import io.github.amichne.kast.query.contract.QueryWorkUsage

/** Binds a canonical page to its own semantic receipt, including the admission debit. */
internal class QueryInvocationExecution(private val operations: QueryOperations) : QueryOperations {
    private sealed interface Receipt {
        data object None : Receipt

        data class Observed(val resources: ResourceBudget, val result: QueryExecutionResult) : Receipt
    }

    private var receipt: Receipt = Receipt.None

    override suspend fun run(request: QueryExecutionRequest): QueryExecutionResult {
        return operations.run(request).also { result -> receipt = Receipt.Observed(request.budget.resources, result) }
    }

    suspend fun page(remaining: QueryBudget, execute: suspend () -> QueryPublishedPage): QueryInvocationPage {
        receipt = Receipt.None
        val page = execute()
        val observed =
            when (val captured = receipt) {
                Receipt.None -> return QueryInvocationPage(page, null)
                is Receipt.Observed -> captured
            }
        val raw = observed.result
        val usage = raw.workUsage as? QueryWorkUsage.Observed ?: return QueryInvocationPage(page, raw)
        val admission =
            when (val parent = remaining.resources) {
                ResourceBudget.RunnerControlled -> 0L
                is ResourceBudget.Bounded ->
                    when (val child = observed.resources) {
                        ResourceBudget.RunnerControlled ->
                            return QueryInvocationPage(
                                page,
                                QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION),
                            )
                        is ResourceBudget.Bounded -> parent.workUnitLimit.value - child.workUnitLimit.value
                    }
            }
        if (admission < 0L || usage.count.value > Long.MAX_VALUE - admission)
            return QueryInvocationPage(
                page,
                QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION),
            )
        val charged =
            when (val count = QueryWorkCount.parse(usage.count.value + admission)) {
                is Refinement.Refined -> raw.observedWork(count.value)
                is Refinement.Rejected ->
                    QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
            }
        return QueryInvocationPage(page, charged)
    }
}
