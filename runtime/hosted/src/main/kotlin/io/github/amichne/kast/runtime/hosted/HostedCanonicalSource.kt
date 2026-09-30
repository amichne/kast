package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.query.protocol.CanonicalSourceReadProtocol
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext

internal suspend fun evaluateHostedSource(
    project: Project,
    services: HostedSemanticServices,
    context: HostedSemanticReadContext,
    request: HostedRequest.Source,
): HostedResponse {
    val session =
        when (val admitted = acquireSourceSession(project, context, request)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted.failure
        }
    when (val admitted = context.preparePublication(session)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return session.rejectSourcePublication(admitted.failure)
    }
    val outcome = executeSourceInput(session, services, context, request)
    val response = fitSourceOutcome(session, outcome, context, request)
    if (response.outcome == io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome.REJECTED) {
        session.discard()
        return response
    }
    return when (val prepared = session.finish(response)) {
        is Refinement.Refined -> response
        is Refinement.Rejected -> session.rejectSourcePublication(prepared.failure)
    }
}

private fun acquireSourceSession(
    project: Project,
    context: HostedSemanticReadContext,
    request: HostedRequest.Source,
): Refinement<HostedSourcePublicationSession, HostedResponse> {
    val outputs =
        when (val admitted = project.service<HostedQueryContinuations>().forEpoch(context.authority, context.limits)) {
            is Refinement.Refined -> admitted.value.sourceState
            is Refinement.Rejected -> return Refinement.Rejected(rejectedHostedEpoch(admitted.failure))
        }
    val token = (request.request.page as? SourceReadPageDocument.Continue)?.continuation
    return when (
        val admitted =
            outputs.acquire(
                request.request,
                context.authority,
                token,
                context.executionBudget.returnedBytes.effective.value,
            ) {
                context.measureRetainedState(HostedRetentionOwner.SOURCE, it)
            }
    ) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected ->
            Refinement.Rejected(
                HostedResponse.Canonical.encode(
                    CanonicalOperationWireBindings.sourceRead,
                    OperationOutcome.Rejected(admitted.failure.sourceRejection()),
                    context.limits,
                    context.executionBudget.returnedBytes.effective,
                )
            )
    }
}

private suspend fun executeSourceInput(
    session: HostedSourcePublicationSession,
    services: HostedSemanticServices,
    context: HostedSemanticReadContext,
    request: HostedRequest.Source,
): HostedSourceOutcome =
    when (val input = session.input) {
        HostedSourceInput.Initial ->
            CanonicalSourceReadProtocol(services.source(session), services.readReferences)
                .execute(request.request, context.authority, services.budgets.hostedSourceBudget)
        is HostedSourceInput.Retained -> input.outcome
        is HostedSourceInput.Published -> input.outcome
    }

private fun fitSourceOutcome(
    session: HostedSourcePublicationSession,
    outcome: HostedSourceOutcome,
    context: HostedSemanticReadContext,
    request: HostedRequest.Source,
): HostedResponse {
    val maximumResults =
        (ResultLimit.parse(minOf(request.request.entityLimit.value, context.executionBudget.results.effective.value))
                as Refinement.Refined)
            .value
    val admittedOutcome = outcome.withSourceBudget(ExecutionBudgetReport.from(context.executionBudget))
    if (session.input is HostedSourceInput.Published) {
        if (outcome.sourceEntityCount() > maximumResults.value)
            return HostedResponse.Rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
        return HostedResponse.Canonical.encode(
            CanonicalOperationWireBindings.sourceRead,
            admittedOutcome,
            context.limits,
            context.executionBudget.returnedBytes.effective,
        )
    }
    return encodeHostedSourceResponse(
        admittedOutcome,
        context.limits,
        maximumResults,
        context.executionBudget.returnedBytes.effective,
    ) { remaining ->
        session.issue(remaining.withSourceBudget(null))
    }
}

private fun HostedSourcePublicationSession.rejectSourcePublication(
    failure: io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
): HostedResponse {
    discard()
    return HostedResponse.ReadRejected(
        failure,
        io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage.RESULT_DETACHED,
    )
}

private fun HostedOutputAcquisitionFailure.sourceRejection(): SourceReadRejection =
    when (this) {
        HostedOutputAcquisitionFailure.UNAVAILABLE -> SourceReadRejection.CONTINUATION_UNAVAILABLE
        HostedOutputAcquisitionFailure.MISMATCH -> SourceReadRejection.CONTINUATION_REQUEST_MISMATCH
        HostedOutputAcquisitionFailure.IN_USE -> SourceReadRejection.CONTINUATION_IN_USE
        HostedOutputAcquisitionFailure.CAPACITY_EXCEEDED -> SourceReadRejection.CONTINUATION_CAPACITY_EXCEEDED
    }
