package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.protocol.CanonicalDiagnosticCheckProtocol
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import io.github.amichne.kast.query.protocol.CanonicalSymbolDiscoverProtocol
import io.github.amichne.kast.query.protocol.CanonicalSymbolInspectProtocol
import io.github.amichne.kast.query.protocol.SourceProtocolBudget
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.intellij.IntellijSourceReadContinuations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext

/** Only the admitted project's pure query service and its bounded read ports enter this graph. */
internal suspend fun evaluateHostedCanonicalQuery(
    project: Project,
    context: HostedSemanticReadContext,
    request: HostedRequest.Read,
    continuations: IntellijSourceReadContinuations,
): HostedResponse {
    val services = HostedSemanticServices(project, context)
    val budgets = services.budgets
    val discovery = services.discovery
    val exact = services.exact
    val references = services.readReferences
    return when (request) {
        is HostedRequest.Query -> evaluateHostedQuery(project, services, context, request, continuations)
        is HostedRequest.Discover ->
            HostedResponse.Canonical.encode(
                CanonicalOperationWireBindings.symbolDiscover,
                CanonicalSymbolDiscoverProtocol(discovery, references)
                    .execute(request.request, context.authority, budgets.hostedDiscoveryBudget),
                limits = context.limits,
            )
        is HostedRequest.Inspect ->
            HostedResponse.Canonical.encode(
                CanonicalOperationWireBindings.symbolInspect,
                CanonicalSymbolInspectProtocol(
                        exact,
                        references,
                        services.revalidationReferences,
                        services.revalidation,
                    )
                    .execute(request.request, context.authority),
                limits = context.limits,
            )
        is HostedRequest.Source -> evaluateHostedSource(project, services, context, request, continuations)
        is HostedRequest.Relation -> evaluateHostedRelation(project, services, context, request)
        is HostedRequest.Traversal -> evaluateHostedTraversal(project, services, context, request)
        is HostedRequest.Diagnostic -> evaluateHostedDiagnostic(project, services, context, request)
    }
}

private suspend fun evaluateHostedQuery(
    project: Project,
    services: HostedSemanticServices,
    context: HostedSemanticReadContext,
    request: HostedRequest.Query,
    continuations: IntellijSourceReadContinuations,
): HostedResponse {
    val queryContinuations = project.service<HostedQueryContinuations>().forEpoch(context.authority, context.limits)
    val token = (request.request as? QueryRunRequest.Resume)?.continuation
    val outcome =
        if (token is QueryExecutionContinuation.Output) {
            queryContinuations.restore(token, context.authority)
        } else {
            CanonicalQueryProtocol(
                    QueryService(
                        discovery = services.discovery,
                        exact = services.exact,
                        source = services.source(continuations),
                        relations = services.relations,
                    ),
                    services.readReferences,
                    queryContinuations.queryState,
                )
                .execute(request.request, context.authority, services.budgets.hostedQueryBudget)
        }
    return encodeHostedQueryResponse(
        semantic =
            outcome.withQueryBudget(
                io.github.amichne.kast.protocol.contract.ExecutionBudgetReport.from(context.executionBudget)
            ),
        limits = context.limits,
        observation = context.observation,
        maximumResults = context.executionBudget.results.effective,
        maximumBytes = context.executionBudget.returnedBytes.effective,
    ) { remaining ->
        queryContinuations.issue(request.request, context.authority, remaining.withQueryBudget(null))
    }
}

private suspend fun evaluateHostedDiagnostic(
    project: Project,
    services: HostedSemanticServices,
    context: HostedSemanticReadContext,
    request: HostedRequest.Diagnostic,
): HostedResponse {
    val retained = project.service<HostedQueryContinuations>().forEpoch(context.authority, context.limits)
    val token = request.request.continuation
    val outcome =
        if (token != null && token.value.startsWith(DIAGNOSTIC_OUTPUT_PREFIX)) {
            retained.diagnosticOutputs.restore(token, request.request, context.authority)
        } else {
            CanonicalDiagnosticCheckProtocol(
                    services.diagnosticScans,
                    services.readReferences,
                    retained.diagnosticCheckpoints,
                )
                .execute(request.request, context.authority, services.budgets.hostedQueryBudget.resources)
        }
    return encodeHostedDiagnosticResponse(
        outcome.withDiagnosticBudget(
            io.github.amichne.kast.protocol.contract.ExecutionBudgetReport.from(context.executionBudget)
        ),
        context.limits,
        context.executionBudget.returnedBytes.effective,
        context.executionBudget.results.effective,
    ) { remaining ->
        retained.diagnosticOutputs.issue(request.request, context.authority, remaining)
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

    val hostedDiscoveryBudget =
        SymbolDiscoveryBudget(
            hostedQueryBudget.resources,
            fixed(SymbolDiscoveryByteLimit.parse(grant.returnedBytes.effective.value)),
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
