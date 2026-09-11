package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.protocol.wire.*
import io.github.amichne.kast.query.contract.*
import io.github.amichne.kast.query.protocol.*
import io.github.amichne.kast.diagnostic.intellij.ProjectBoundIntellijDiagnosticPorts
import io.github.amichne.kast.diagnostic.service.DiagnosticService
import io.github.amichne.kast.traversal.service.traversalOperations
import io.github.amichne.kast.traversal.contract.*
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.intellij.ProjectBoundIntellijRelationPort
import io.github.amichne.kast.relation.service.RelationService
import io.github.amichne.kast.source.contract.*
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.intellij.ProjectBoundIntellijSourceReadPort
import io.github.amichne.kast.source.intellij.IntellijSourceReadContinuations
import io.github.amichne.kast.source.service.SourceReadService
import io.github.amichne.kast.symbol.intellij.ProjectBoundIntellijSymbolPorts
import io.github.amichne.kast.symbol.service.SymbolDiscoveryService
import io.github.amichne.kast.symbol.service.SymbolExactService
import io.github.amichne.kast.workspace.contract.SemanticReadValidation
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext

/** Only the admitted project's pure query service and its bounded read ports enter this graph. */
internal suspend fun evaluateHostedCanonicalQuery(
    project: Project,
    context: HostedSemanticReadContext,
    request: HostedRequest.Read,
    continuations: IntellijSourceReadContinuations,
): String {
    val budgets = HostedSemanticBudgets(context.limits)
    val symbols = ProjectBoundIntellijSymbolPorts.create(project, context.authority, context.model, context.sourceFiles, context.observation, context.limits)
    val discovery = SymbolDiscoveryService(context.validation, symbols.discovery)
    val exact = SymbolExactService(context.validation, symbols.exact)
    val source = SourceReadService(SourceReadContextPort { expected ->
        when (context.validation.validate(expected)) {
            SemanticReadValidation.CURRENT -> Refinement.Refined(SourceReadContext.Live(context.authority))
            SemanticReadValidation.UNAVAILABLE -> Refinement.Rejected(SourceReadRejection.WORKSPACE_NOT_READY)
            SemanticReadValidation.ROOT_MISMATCH -> Refinement.Rejected(SourceReadRejection.WORKSPACE_ROOT_MISMATCH)
            SemanticReadValidation.MOVED -> Refinement.Rejected(SourceReadRejection.STALE_GENERATION)
        }
    }, ProjectBoundIntellijSourceReadPort.create(project, context.authority, context.model, context.sourceFiles, continuations, context.limits, context.observation))
    val relations = RelationService(context.validation,
        ProjectBoundIntellijRelationPort.create(project, context.authority, context.model, context.sourceFiles, context.observation, context.limits))
    val references = CanonicalQueryReferences(context.model)
    val encoded = when (request) {
        is HostedRequest.Query -> CanonicalOperationWireBindings.queryRun.encodeOutcome(
            CanonicalQueryProtocol(QueryService(discovery, exact, source, relations), references).execute(request.request, context.authority, budgets.hostedQueryBudget))
        is HostedRequest.Discover -> CanonicalOperationWireBindings.symbolDiscover.encodeOutcome(
            CanonicalSymbolDiscoverProtocol(discovery, references).execute(request.request, context.authority, budgets.hostedDiscoveryBudget))
        is HostedRequest.Inspect -> CanonicalOperationWireBindings.symbolInspect.encodeOutcome(
            CanonicalSymbolInspectProtocol(exact, references).execute(request.request, context.authority))
        is HostedRequest.Source -> CanonicalOperationWireBindings.sourceRead.encodeOutcome(
            CanonicalSourceReadProtocol(source, references).execute(request.request, context.authority, budgets.hostedSourceBudget))
        is HostedRequest.Relation -> CanonicalOperationWireBindings.relationRead.encodeOutcome(
            CanonicalRelationReadProtocol(relations, references).execute(request.request, context.authority, budgets.hostedRelationBudget))
        is HostedRequest.Traversal -> CanonicalOperationWireBindings.traversalRun.encodeOutcome(
            CanonicalTraversalRunProtocol(traversalOperations(relations), references).execute(request.request, context.authority, budgets.hostedTraversalBudget))
        is HostedRequest.Diagnostic -> {
            val diagnostics = ProjectBoundIntellijDiagnosticPorts.create(project, context.authority, context.model, context.sourceFiles, context.limits)
            CanonicalOperationWireBindings.diagnosticCheck.encodeOutcome(
                CanonicalDiagnosticCheckProtocol(DiagnosticService(context.validation, diagnostics.compiler), references, diagnostics.scopes)
                    .execute(request.request, context.authority, budgets.hostedQueryBudget.resources.resultLimit))
        }
    }
    return when (encoded) {
        is WireEncoding.Encoded -> if (encoded.document.toByteArray(Charsets.UTF_8).size <= context.limits[ReadLimitParameter.HOST_RESPONSE_BYTES].value) encoded.document
            else HostedRequests.rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
        is WireEncoding.Rejected -> HostedRequests.rejected(HostedEndpointFailure.RESPONSE_REJECTED)
    }
}

/** Budgets are projected only from an admitted, immutable policy. */
private class HostedSemanticBudgets(private val limits: ReadLimits) {
val hostedQueryBudget = QueryBudget(
    ResourceBudget(fixed(ResultLimit.parse(limits[ReadLimitParameter.SEMANTIC_RESULTS].value)), fixed(WorkUnitLimit.parse(limits[ReadLimitParameter.SEMANTIC_WORK].value.toLong())), fixed(ElapsedTimeLimitMillis.parse(limits[ReadLimitParameter.SEMANTIC_MILLIS].value.toLong()))),
    fixed(QueryByteLimit.parse(limits[ReadLimitParameter.SEMANTIC_RETURNED_BYTES].value.toLong())),
)

val hostedDiscoveryBudget = SymbolDiscoveryBudget(hostedQueryBudget.resources, fixed(SymbolDiscoveryByteLimit.parse(limits[ReadLimitParameter.SEMANTIC_RETURNED_BYTES].value.toLong())))
val hostedRelationBudget = RelationBudget(hostedQueryBudget.resources, fixed(RelationByteLimit.parse(limits[ReadLimitParameter.SEMANTIC_RETURNED_BYTES].value.toLong())))
val hostedSourceBudget = SourceProtocolBudget(fixed(SourceEntityLimit.parse(limits[ReadLimitParameter.SOURCE_ENTITIES].value)), fixed(SourceTextByteLimit.parse(limits[ReadLimitParameter.SOURCE_RETURNED_BYTES].value.toLong())))
val hostedTraversalBudget = TraversalBudget(
    hostedQueryBudget.resources.resultLimit, fixed(TraversalByteLimit.parse(limits[ReadLimitParameter.SEMANTIC_RETURNED_BYTES].value.toLong())),
    hostedQueryBudget.resources.workUnitLimit, hostedQueryBudget.resources.elapsedTimeLimit,
    fixed(TraversalDepthLimit.parse(limits[ReadLimitParameter.TRAVERSAL_DEPTH].value)), fixed(TraversalFrontierLimit.parse(limits[ReadLimitParameter.TRAVERSAL_FRONTIER].value)), hostedRelationBudget,
)

}

private fun <Value> fixed(value: Refinement<Value, *>): Value = when (value) {
    is Refinement.Refined -> value.value
    is Refinement.Rejected -> error("Admitted hosted limit lost its positive bound")
}
