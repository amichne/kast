package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.AdmittedRelationReadRejection
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.protocol.contract.reason
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
            CanonicalRelationReadProtocol(services.relations, services.readReferences)
                .execute(request.request, context.authority, services.budgets.hostedRelationBudget)
        }
    val maximum =
        (ResultLimit.parse(
                minOf(request.request.limit.value, services.budgets.hostedRelationBudget.resources.resultLimit.value)
            ) as Refinement.Refined)
            .value
    val report = ExecutionBudgetReport.from(context.executionBudget)
    return encodeHostedRelationResponse(
        outcome.withRelationBudget(report),
        context.limits,
        maximum,
        context.executionBudget.returnedBytes.effective,
    ) { suffix ->
        pages.issue(request.request, context.authority, suffix.withRelationBudget(null))
    }
}

internal fun HostedRelationOutcome.withRelationBudget(report: ExecutionBudgetReport?): HostedRelationOutcome =
    when (this) {
        is OperationOutcome.Complete ->
            OperationOutcome.Complete(evidence.copy(payload = evidence.payload.copy(executionBudget = report)))
        is OperationOutcome.Qualified ->
            OperationOutcome.Qualified(
                evidence.copy(payload = evidence.payload.copy(executionBudget = report)),
                qualification,
            )
        is OperationOutcome.Rejected ->
            if (report == null) this
            else OperationOutcome.Rejected(AdmittedRelationReadRejection(reason.reason(), report))
    }
