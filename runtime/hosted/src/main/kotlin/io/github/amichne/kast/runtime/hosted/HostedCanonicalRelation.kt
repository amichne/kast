package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.query.protocol.CanonicalRelationReadProtocol
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext

internal suspend fun evaluateHostedRelation(
    project: Project,
    services: HostedSemanticServices,
    context: HostedSemanticReadContext,
    request: HostedRequest.Relation,
): HostedResponse {
    val pages = project.service<HostedQueryContinuations>().forEpoch(context.authority, context.limits).relationOutputs
    val position = request.request.position
    val outcome =
        if (
            position is RelationReadPositionDocument.Resume &&
                position.continuation.value.startsWith(RelationContinuationDocument.OUTPUT_PREFIX)
        ) {
            val token =
                when (val admitted = ProtocolText.parse(position.continuation.value)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return HostedResponse.Rejected(HostedEndpointFailure.INVALID_REQUEST)
                }
            pages.restore(token, request.request, context.authority)
        } else {
            CanonicalRelationReadProtocol(services.relations, services.references)
                .execute(request.request, context.authority, services.budgets.hostedRelationBudget)
        }
    val maximum =
        (ResultLimit.parse(
                minOf(request.request.limit.value, services.budgets.hostedRelationBudget.resources.resultLimit.value)
            ) as Refinement.Refined)
            .value
    return encodeHostedRelationResponse(outcome, context.limits, maximum) { suffix ->
        pages.issue(request.request, context.authority, suffix)
    }
}
