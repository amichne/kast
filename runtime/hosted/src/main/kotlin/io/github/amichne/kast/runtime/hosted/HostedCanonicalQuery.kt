package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryPresentationExecution
import io.github.amichne.kast.query.protocol.CanonicalDiagnosticCheckProtocol
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import io.github.amichne.kast.query.protocol.SourceProtocolBudget
import io.github.amichne.kast.query.protocol.peerRejection
import io.github.amichne.kast.query.protocol.selectPeerSites
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext
import kotlinx.coroutines.currentCoroutineContext

/** Only the admitted project's pure query service and its bounded read ports enter this graph. */
internal suspend fun evaluateHostedCanonicalQuery(
    project: Project,
    context: HostedSemanticReadContext,
    request: HostedRequest.Read,
): HostedResponse {
    val services =
        when (val admitted = admitHostedSemanticServices(project, context)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejectedHostedEpoch(admitted.failure)
        }
    return when (request) {
        is HostedRequest.Query -> evaluateHostedQuery(project, services, context, request)
        is HostedRequest.Source -> evaluateHostedSource(project, services, context, request)
        is HostedRequest.Diagnostic -> evaluateHostedDiagnostic(project, services, context, request)
    }
}

private suspend fun evaluateHostedQuery(
    project: Project,
    services: HostedSemanticServices,
    context: HostedSemanticReadContext,
    request: HostedRequest.Query,
): HostedResponse {
    val peerAdmissions =
        when (val admitted = prepareHostedPeerSites(request.request, services, context)) {
            is HostedPeerSiteAdmissions.Admitted -> admitted.values
            is HostedPeerSiteAdmissions.HostedRejected -> return admitted.response
            is HostedPeerSiteAdmissions.QueryRejected -> return rejectedPeerQuery(admitted.cause, context)
            is HostedPeerSiteAdmissions.ProofRejected ->
                return rejectedPeerQuery(
                    admitted.cause.peerRejection(admitted.position),
                    context,
                )
        }
    val queryContinuations =
        when (val admitted = project.service<HostedQueryContinuations>().forEpoch(context.authority, context.limits)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejectedHostedEpoch(admitted.failure)
        }
    val callerContext = currentCoroutineContext()
    val publication = HostedQueryPublicationSession(context, request.request)
    return QueryPresentationExecution.evaluateAndFit(
        evaluate = { presentationOwner ->
            hostedCanonicalQueryProtocol(
                    services,
                    context,
                    queryContinuations,
                    publication,
                    presentationOwner,
                    peerAdmissions,
                )
                .execute(
                    request.request,
                    context.authority,
                    services.budgets.hostedQueryBudget,
                    hostedSymbolInvocationPolicy(context, services.budgets, callerContext),
                )
        },
        fit = { outcome -> fitHostedQueryOutcome(outcome, context, publication) },
    )
}

private fun fitHostedQueryOutcome(
    outcome: HostedQueryOutcome,
    context: HostedSemanticReadContext,
    publication: HostedQueryPublicationSession,
): HostedResponse {
    context.observation.phase(io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase.ENCODING)
    return encodeHostedQueryResponse(
        semantic =
            outcome
                .withQueryDiagnosticIdentity(context.readTrace)
                .withQueryBudget(
                    io.github.amichne.kast.protocol.contract.ExecutionBudgetReport.from(context.executionBudget)
                ),
        limits = context.limits,
        observation = context.observation,
        maximumResults = context.executionBudget.results.effective,
        maximumBytes = context.executionBudget.returnedBytes.effective,
        published = publication::fitted,
        retain = publication::retain,
    )
}

private suspend fun prepareHostedPeerSites(
    request: QueryRunRequest,
    services: HostedSemanticServices,
    context: HostedSemanticReadContext,
): HostedPeerSiteAdmissions {
    val selections =
        when (val admitted = request.hostedPeerSelections(context.authority)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return HostedPeerSiteAdmissions.QueryRejected(admitted.failure)
        }
    return acquireHostedPeerSites(
        selections,
        services.budgets.hostedQueryBudget,
        services.acquisitionAccounting,
        observation = context.observation,
    )
}

private fun hostedCanonicalQueryProtocol(
    services: HostedSemanticServices,
    context: HostedSemanticReadContext,
    continuations: HostedQueryContinuations.Active,
    publication: HostedQueryPublicationSession,
    presentation: QueryPresentationExecution,
    peers: List<io.github.amichne.kast.query.contract.QueryImpactPeerSiteAdmission>,
): CanonicalQueryProtocol =
    CanonicalQueryProtocol(
        QueryService(
            discovery = services.discovery,
            exact = services.exact,
            source = services.source(io.github.amichne.kast.source.contract.SourceReadContinuationPort.Cursorless),
            relations = services.relations,
            traversal = hostedTraversalOperations(services.relations, context.observation),
            traversalCeiling = services.budgets.hostedTraversalBudget,
            valueFlow = services.valueFlow,
            presentation = presentation,
            checkpointObservation = hostedQueryCheckpointStorageObservation(context.observation),
        ),
        services.readReferences,
        continuations.queryState,
        publication,
        services.producerSeeds,
        hostedQueryResultRetentionObservation(context.observation),
        peerSiteAdmissions = peers,
    )

internal fun QueryRunRequest.hostedPeerSelections(
    authority: io.github.amichne.kast.workspace.contract.SemanticReadAuthority
): Refinement<List<io.github.amichne.kast.query.protocol.QueryImpactPeerSelection>, QueryRunRejection> =
    when (this) {
        is QueryRunRequest.Run ->
            when (val source = from) {
                is QueryFromDocument.Impact -> source.investigation.selectPeerSites(authority)
                else -> Refinement.Refined(emptyList())
            }
        is QueryRunRequest.Resume,
        is QueryRunRequest.ReadResult -> Refinement.Refined(emptyList())
    }

private fun rejectedPeerQuery(cause: QueryRunRejection, context: HostedSemanticReadContext): HostedResponse {
    val rejected: HostedQueryOutcome = OperationOutcome.Rejected(cause)
    return encodeHostedQueryResponse(
        rejected.withQueryBudget(ExecutionBudgetReport.from(context.executionBudget)),
        context.limits,
        context.observation,
        context.executionBudget.results.effective,
        context.executionBudget.returnedBytes.effective,
    )
}

private suspend fun evaluateHostedDiagnostic(
    project: Project,
    services: HostedSemanticServices,
    context: HostedSemanticReadContext,
    request: HostedRequest.Diagnostic,
): HostedResponse {
    val retained =
        when (val admitted = project.service<HostedQueryContinuations>().forEpoch(context.authority, context.limits)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejectedHostedEpoch(admitted.failure)
        }
    val publication = HostedDiagnosticPublicationSession(context)
    val outcome =
        CanonicalDiagnosticCheckProtocol(
                services.diagnosticScans,
                services.readReferences,
                retained.diagnosticCheckpoints,
                publication,
            )
            .execute(request.request, context.authority, services.budgets.hostedQueryBudget.resources)
    val response =
        encodeHostedDiagnosticResponse(
            outcome.withDiagnosticBudget(
                io.github.amichne.kast.protocol.contract.ExecutionBudgetReport.from(context.executionBudget)
            ),
            context.limits,
            context.executionBudget.returnedBytes.effective,
            context.executionBudget.results.effective,
            publication::retain,
        )
    if (response.outcome == io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome.REJECTED) {
        publication.discard()
        return response
    }
    return when (val fitted = publication.fitted(response)) {
        is Refinement.Refined -> response
        is Refinement.Rejected -> {
            publication.discard()
            HostedResponse.ReadRejected(
                fitted.failure,
                io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage.RESULT_DETACHED,
            )
        }
    }
}

/** Budgets are projected only from an admitted, immutable policy. */
internal class HostedSemanticBudgets(
    private val limits: ReadLimits,
    grant: io.github.amichne.kast.kernel.AdmittedExecutionBudget,
) {
    val hostedQueryBudget =
        QueryBudget(
            grant.resources,
            fixed(QueryByteLimit.parse(grant.returnedBytes.effective.value)),
            fixed(QueryByteLimit.parse(limits[ReadLimitParameter.QUERY_CHECKPOINT_BYTES].value.toLong())),
        )

    val hostedRelationBudget =
        RelationBudget(
            hostedQueryBudget.resources,
            fixed(RelationByteLimit.parse(grant.returnedBytes.effective.value)),
        )
    val hostedSourceBudget =
        SourceProtocolBudget(
            grant.resources,
            fixed(SourceTextByteLimit.parse(grant.returnedBytes.effective.value)),
        )
    val hostedTraversalBudget =
        TraversalBudget(
            hostedQueryBudget.resources.resultLimit,
            fixed(TraversalByteLimit.parse(grant.returnedBytes.effective.value)),
            hostedQueryBudget.resources.workUnitLimit,
            hostedQueryBudget.resources.elapsedTimeLimit,
            fixed(TraversalDepthLimit.parse(limits[ReadLimitParameter.TRAVERSAL_DEPTH].value)),
            fixed(TraversalFrontierLimit.parse(limits[ReadLimitParameter.TRAVERSAL_FRONTIER].value)),
            hostedRelationBudget,
        )
}

private fun <Value> fixed(value: Refinement<Value, *>): Value =
    when (value) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> error("Admitted hosted limit lost its positive bound")
    }

internal fun rejectedHostedEpoch(
    failure: io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure
): HostedResponse.ReadRejected =
    HostedResponse.ReadRejected(
        io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure.LiveAuthority(failure),
        io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage.CONTENT_REVALIDATION,
    )
