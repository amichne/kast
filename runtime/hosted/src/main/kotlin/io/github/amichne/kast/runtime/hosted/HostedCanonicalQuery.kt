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
    val symbols = ProjectBoundIntellijSymbolPorts.create(project, context.authority, context.model, context.sourceFiles)
    val discovery = SymbolDiscoveryService(context.validation, symbols.discovery)
    val exact = SymbolExactService(context.validation, symbols.exact)
    val source = SourceReadService(SourceReadContextPort { expected ->
        when (context.validation.validate(expected)) {
            SemanticReadValidation.CURRENT -> Refinement.Refined(SourceReadContext.Live(context.authority))
            SemanticReadValidation.UNAVAILABLE -> Refinement.Rejected(SourceReadRejection.WORKSPACE_NOT_READY)
            SemanticReadValidation.ROOT_MISMATCH -> Refinement.Rejected(SourceReadRejection.WORKSPACE_ROOT_MISMATCH)
            SemanticReadValidation.MOVED -> Refinement.Rejected(SourceReadRejection.STALE_GENERATION)
        }
    }, ProjectBoundIntellijSourceReadPort.create(project, context.authority, context.model, context.sourceFiles, continuations))
    val relations = RelationService(context.validation,
        ProjectBoundIntellijRelationPort.create(project, context.authority, context.model, context.sourceFiles))
    val references = CanonicalQueryReferences(context.model)
    val encoded = when (request) {
        is HostedRequest.Query -> CanonicalOperationWireBindings.queryRun.encodeOutcome(
            CanonicalQueryProtocol(QueryService(discovery, exact, source, relations), references).execute(request.request, context.authority, hostedQueryBudget))
        is HostedRequest.Discover -> CanonicalOperationWireBindings.symbolDiscover.encodeOutcome(
            CanonicalSymbolDiscoverProtocol(discovery, references).execute(request.request, context.authority, hostedDiscoveryBudget))
        is HostedRequest.Inspect -> CanonicalOperationWireBindings.symbolInspect.encodeOutcome(
            CanonicalSymbolInspectProtocol(exact, references).execute(request.request, context.authority))
        is HostedRequest.Source -> CanonicalOperationWireBindings.sourceRead.encodeOutcome(
            CanonicalSourceReadProtocol(source, references).execute(request.request, context.authority, hostedSourceBudget))
        is HostedRequest.Relation -> CanonicalOperationWireBindings.relationRead.encodeOutcome(
            CanonicalRelationReadProtocol(relations, references).execute(request.request, context.authority, hostedRelationBudget))
        is HostedRequest.Traversal -> CanonicalOperationWireBindings.traversalRun.encodeOutcome(
            CanonicalTraversalRunProtocol(traversalOperations(relations), references).execute(request.request, context.authority, hostedTraversalBudget))
        is HostedRequest.Diagnostic -> {
            val diagnostics = ProjectBoundIntellijDiagnosticPorts.create(project, context.authority, context.model, context.sourceFiles)
            CanonicalOperationWireBindings.diagnosticCheck.encodeOutcome(
                CanonicalDiagnosticCheckProtocol(DiagnosticService(context.validation, diagnostics.compiler), references, diagnostics.scopes)
                    .execute(request.request, context.authority, hostedQueryBudget.resources.resultLimit))
        }
    }
    return when (encoded) {
        is WireEncoding.Encoded -> if (encoded.document.toByteArray(Charsets.UTF_8).size <= 65_536) encoded.document
            else HostedRequests.rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
        is WireEncoding.Rejected -> HostedRequests.rejected(HostedEndpointFailure.RESPONSE_REJECTED)
    }
}

/** Matches the endpoint deadline and leaves frame space for typed coverage and evidence metadata. */
private val hostedQueryBudget = QueryBudget(
    ResourceBudget(fixed(ResultLimit.parse(128)), fixed(WorkUnitLimit.parse(100_000)), fixed(ElapsedTimeLimitMillis.parse(2_000))),
    fixed(QueryByteLimit.parse(49_152)),
)

private val hostedDiscoveryBudget = SymbolDiscoveryBudget(hostedQueryBudget.resources, fixed(SymbolDiscoveryByteLimit.parse(49_152)))
private val hostedRelationBudget = RelationBudget(hostedQueryBudget.resources, fixed(RelationByteLimit.parse(49_152)))
private val hostedSourceBudget = SourceProtocolBudget(fixed(SourceEntityLimit.parse(128)), fixed(SourceTextByteLimit.parse(49_152)))
private val hostedTraversalBudget = TraversalBudget(
    hostedQueryBudget.resources.resultLimit, fixed(TraversalByteLimit.parse(49_152)),
    hostedQueryBudget.resources.workUnitLimit, hostedQueryBudget.resources.elapsedTimeLimit,
    fixed(TraversalDepthLimit.parse(16)), fixed(TraversalFrontierLimit.parse(128)), hostedRelationBudget,
)

private fun <Value> fixed(value: Refinement<Value, *>): Value = when (value) {
    is Refinement.Refined -> value.value
    is Refinement.Rejected -> error("Invalid fixed hosted query budget")
}
