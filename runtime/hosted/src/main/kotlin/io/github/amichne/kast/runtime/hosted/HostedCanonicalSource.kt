package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.query.protocol.CanonicalSourceReadProtocol
import io.github.amichne.kast.source.intellij.IntellijSourceReadContinuations
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext

internal suspend fun evaluateHostedSource(
    project: Project,
    services: HostedSemanticServices,
    context: HostedSemanticReadContext,
    request: HostedRequest.Source,
    continuations: IntellijSourceReadContinuations,
): HostedResponse {
    val outputs = project.service<HostedQueryContinuations>().forEpoch(context.authority, context.limits).sourceOutputs
    val page = request.request.page
    val outcome =
        if (page is SourceReadPageDocument.Continue && page.continuation.value.startsWith(SOURCE_OUTPUT_PREFIX)) {
            outputs.restore(page.continuation, request.request, context.authority)
        } else {
            CanonicalSourceReadProtocol(services.source(continuations), services.references)
                .execute(request.request, context.authority, services.budgets.hostedSourceBudget)
        }
    val maximumResults =
        (ResultLimit.parse(
                minOf(
                    request.request.entityLimit.value,
                    context.executionBudget.results.effective.value,
                )
            ) as Refinement.Refined)
            .value
    return encodeHostedSourceResponse(
        outcome.withSourceBudget(ExecutionBudgetReport.from(context.executionBudget)),
        context.limits,
        maximumResults,
        context.executionBudget.returnedBytes.effective,
    ) { remaining ->
        outputs.issue(request.request, context.authority, remaining.withSourceBudget(null))
    }
}
