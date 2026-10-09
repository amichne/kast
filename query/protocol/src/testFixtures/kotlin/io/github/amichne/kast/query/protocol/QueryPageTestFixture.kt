package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Exercises one internal evaluator page; its outcome does not establish public query completion. */
suspend fun CanonicalQueryProtocol.executeQueryPage(
    request: QueryRunRequest,
    lease: SemanticReadAuthority,
    budget: QueryBudget,
): QueryPublishedPage = executePage(request, lease, budget)
