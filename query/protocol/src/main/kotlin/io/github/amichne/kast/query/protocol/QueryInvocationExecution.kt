package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryWorkCount
import io.github.amichne.kast.query.contract.QueryWorkUsage

/** Binds a canonical page to its own semantic receipt, including the admission debit. */
internal class QueryInvocationExecution(private val operations: QueryOperations) : QueryOperations {
    private var receipt: QueryExecutionResult? = null
    private var admittedWork = 0L

    override suspend fun run(request: QueryExecutionRequest): QueryExecutionResult {
        admittedWork = request.budget.resources.workUnitLimit.value
        return operations.run(request).also { result -> receipt = result }
    }

    suspend fun page(remaining: QueryBudget, execute: suspend () -> QueryPublishedPage): QueryInvocationPage {
        receipt = null
        val page = execute()
        val raw = receipt
        val usage = raw?.workUsage as? QueryWorkUsage.Observed ?: return QueryInvocationPage(page, raw)
        val admission = remaining.resources.workUnitLimit.value - admittedWork
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
