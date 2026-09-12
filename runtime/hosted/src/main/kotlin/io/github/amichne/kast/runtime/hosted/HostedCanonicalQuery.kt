package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.protocol.CanonicalDiagnosticCheckProtocol
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import io.github.amichne.kast.query.protocol.CanonicalRelationReadProtocol
import io.github.amichne.kast.query.protocol.CanonicalSourceReadProtocol
import io.github.amichne.kast.query.protocol.CanonicalSymbolDiscoverProtocol
import io.github.amichne.kast.query.protocol.CanonicalSymbolInspectProtocol
import io.github.amichne.kast.query.protocol.CanonicalTraversalRunProtocol
import io.github.amichne.kast.query.protocol.SourceProtocolBudget
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.intellij.IntellijSourceReadContinuations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.service.traversalOperations
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
    val source = services.source(continuations)
    val relations = services.relations
    val references = services.references
    return when (request) {
        is HostedRequest.Query ->
            HostedResponse.Canonical.encode(
                CanonicalOperationWireBindings.queryRun,
                CanonicalQueryProtocol(QueryService(discovery, exact, source, relations), references)
                    .execute(request.request, context.authority, budgets.hostedQueryBudget),
                limits = context.limits,
            )
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
                CanonicalSymbolInspectProtocol(exact, references).execute(request.request, context.authority),
                limits = context.limits,
            )
        is HostedRequest.Source ->
            HostedResponse.Canonical.encode(
                CanonicalOperationWireBindings.sourceRead,
                CanonicalSourceReadProtocol(source, references)
                    .execute(request.request, context.authority, budgets.hostedSourceBudget),
                limits = context.limits,
            )
        is HostedRequest.Relation ->
            HostedResponse.Canonical.encode(
                CanonicalOperationWireBindings.relationRead,
                CanonicalRelationReadProtocol(relations, references)
                    .execute(request.request, context.authority, budgets.hostedRelationBudget),
                limits = context.limits,
            )
        is HostedRequest.Traversal ->
            HostedResponse.Canonical.encode(
                CanonicalOperationWireBindings.traversalRun,
                CanonicalTraversalRunProtocol(traversalOperations(relations), references)
                    .execute(request.request, context.authority, budgets.hostedTraversalBudget),
                limits = context.limits,
            )
        is HostedRequest.Diagnostic -> {
            HostedResponse.Canonical.encode(
                CanonicalOperationWireBindings.diagnosticCheck,
                CanonicalDiagnosticCheckProtocol(
                        services.diagnostics,
                        references,
                        services.diagnosticPorts.scopes,
                    )
                    .execute(request.request, context.authority, budgets.hostedQueryBudget.resources.resultLimit),
                limits = context.limits,
            )
        }
    }
}

/** Budgets are projected only from an admitted, immutable policy. */
internal class HostedSemanticBudgets(private val limits: ReadLimits) {
    val hostedQueryBudget =
        QueryBudget(
            ResourceBudget(
                fixed(ResultLimit.parse(limits[ReadLimitParameter.SEMANTIC_RESULTS].value)),
                fixed(WorkUnitLimit.parse(limits[ReadLimitParameter.SEMANTIC_WORK].value.toLong())),
                fixed(ElapsedTimeLimitMillis.parse(limits[ReadLimitParameter.SEMANTIC_MILLIS].value.toLong())),
            ),
            fixed(QueryByteLimit.parse(limits[ReadLimitParameter.SEMANTIC_RETURNED_BYTES].value.toLong())),
        )

    val hostedDiscoveryBudget =
        SymbolDiscoveryBudget(
            hostedQueryBudget.resources,
            fixed(SymbolDiscoveryByteLimit.parse(limits[ReadLimitParameter.SEMANTIC_RETURNED_BYTES].value.toLong())),
        )
    val hostedRelationBudget =
        RelationBudget(
            hostedQueryBudget.resources,
            fixed(RelationByteLimit.parse(limits[ReadLimitParameter.SEMANTIC_RETURNED_BYTES].value.toLong())),
        )
    val hostedSourceBudget =
        SourceProtocolBudget(
            fixed(SourceEntityLimit.parse(limits[ReadLimitParameter.SOURCE_ENTITIES].value)),
            fixed(SourceTextByteLimit.parse(limits[ReadLimitParameter.SOURCE_RETURNED_BYTES].value.toLong())),
        )
    val hostedTraversalBudget =
        TraversalBudget(
            hostedQueryBudget.resources.resultLimit,
            fixed(TraversalByteLimit.parse(limits[ReadLimitParameter.SEMANTIC_RETURNED_BYTES].value.toLong())),
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
