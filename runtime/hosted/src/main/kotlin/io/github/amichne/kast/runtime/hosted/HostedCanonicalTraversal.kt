package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalRunFailure
import io.github.amichne.kast.protocol.contract.TraversalRunPositionDocument
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.query.protocol.CanonicalTraversalRunProtocol
import io.github.amichne.kast.traversal.service.traversalOperations
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext

internal suspend fun evaluateHostedTraversal(
    project: Project,
    services: HostedSemanticServices,
    context: HostedSemanticReadContext,
    request: HostedRequest.Traversal,
): HostedResponse {
    val maximumResults =
        when (val value = admitHostedTraversalOutputLimit(request.request.maximumResults, context.executionBudget)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected ->
                return HostedResponse.Canonical.encode(
                    CanonicalOperationWireBindings.traversalRun,
                    OperationOutcome.Rejected(value.failure),
                    context.limits,
                    context.executionBudget.returnedBytes.effective,
                )
        }
    val outputs =
        project.service<HostedQueryContinuations>().forEpoch(context.authority, context.limits).traversalOutputs
    val position = request.request.position
    val outcome =
        if (
            position is TraversalRunPositionDocument.Resume &&
                position.continuation.value.startsWith(TraversalContinuationDocument.OUTPUT_PREFIX)
        ) {
            val token = (ProtocolText.parse(position.continuation.value) as Refinement.Refined).value
            outputs.restore(token, request.request, context.authority)
        } else {
            CanonicalTraversalRunProtocol(traversalOperations(services.relations), services.references)
                .execute(request.request, context.authority, services.budgets.hostedTraversalBudget)
        }
    return encodeHostedTraversalResponse(
        outcome.withTraversalBudget(ExecutionBudgetReport.from(context.executionBudget)),
        context.limits,
        maximumResults,
        context.executionBudget.returnedBytes.effective,
    ) { remaining ->
        outputs.issue(request.request, context.authority, remaining.withTraversalBudget(null))
    }
}

/** Legacy page limits are refined under the same grant already admitted for semantic execution. */
internal fun admitHostedTraversalOutputLimit(
    requested: ProtocolCount,
    grant: AdmittedExecutionBudget,
): Refinement<ResultLimit, TraversalRunFailure> =
    when (val admitted = ResultLimit.parse(minOf(requested.value, grant.results.effective.value))) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(TraversalRunRejection.PLAN_REJECTED)
    }
