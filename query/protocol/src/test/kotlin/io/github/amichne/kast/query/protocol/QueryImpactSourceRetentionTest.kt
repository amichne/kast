package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueModelDeclarationRead
import io.github.amichne.kast.relation.contract.ValueProducerSeed
import io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Scripted detached compiler observations establish the production admission rule, not installed K2 behavior. */
class QueryImpactSourceRetentionTest {
    private val owner = endpoint("investigate", 0, 200)
    private val callable = endpoint("encrypt", 210, 250)
    private val references = CanonicalQueryReferences()
    private val budget =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(20).refined(),
                WorkUnitLimit.parse(10).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            QueryByteLimit.parse(100000).refined(),
        )

    @Test
    fun `retained impact question and automatic checkpoint keep seed proof without reacquiring it`() = runTest {
        var seedCalls = 0
        var executions = 0
        val compiler = seedCompiler { assertEquals(0, seedCalls++) }
        val run = request(document()).copy(retention = QueryRetentionModeDocument.RETAIN)
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { execution ->
                    executionResult(execution, executions++ == 0)
                        .observedWork(io.github.amichne.kast.query.contract.QueryWorkCount.parse(1).refined())
                },
                references,
                producerSeeds = compiler,
            )
        val outcome =
            protocol.execute(run, owner.lease, budget, retainedQueryTestPolicy(budget)) as OperationOutcome.Rejected
        val rejection = outcome.reason as io.github.amichne.kast.protocol.contract.QueryRunRejection.CompletionUnproven
        val retained =
            rejection.evidence as io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument.Retained
        assertEquals(QueryQuestionDocument.from(run), retained.question)
        assertEquals(1, seedCalls)
        assertEquals(2, executions)
        val read =
            protocol.executePage(QueryRunRequest.ReadResult.valuePaths(retained.result), owner.lease, budget)
                as OperationOutcome.Qualified
        assertEquals(retained.question, read.evidence.payload.question)
        assertEquals(1, seedCalls)
        assertEquals(2, executions)
    }

    private fun seedCompiler(observeSeed: () -> Unit): ValueProducerSeedCompilerPort {
        return object : ValueProducerSeedCompilerPort {
            override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead {
                observeSeed()
                return ValueProducerSeedRead.Seeded(proof(request), work(3))
            }

            override suspend fun revalidate(
                selector: SymbolSelector,
                budget: RelationBudget,
            ): ValueModelDeclarationRead = error("unexpected declaration")
        }
    }

    private fun executionResult(execution: QueryExecutionRequest, resumable: Boolean): QueryExecutionResult {
        val result = observedResult(execution)
        val coverage =
            QueryCoverage.Qualified.create(
                    QueryCount.parse(1).refined(),
                    setOf(QueryLimitation.IMPACT_COVERAGE_UNPROVEN),
                )
                .refined()
        return if (resumable)
            QueryExecutionResult.Qualified(
                result,
                coverage,
                QueryContinuationState.Resumable(
                    object : QueryCheckpoint {
                        override val plan = execution.plan
                        override val lease = execution.lease
                        override val retainedBytes = 1024L
                    }
                ),
            )
        else QueryExecutionResult.Qualified(result, coverage)
    }

    private fun observedResult(execution: QueryExecutionRequest): QueryResult {
        val plan = execution.plan as AdmittedQueryPlan.Impact
        val producer = plan.source.producers.single()
        val site = producer.site
        val selector = (site.enclosing as RelationEndpoint.Subject).selector
        val domain =
            RelationRequest.start(
                selector,
                RelationMeaning.References,
                RelationBudget(execution.budget.resources, RelationByteLimit.parse(100000).refined()),
                plan.source.domain,
            )
        val obligation = ValueFlowObligation(site, ValueFlowUnsupportedCause.UNMODELED_CALL)
        val observed =
            ValueFlowStep.fromCompiler(
                    site,
                    emptyList(),
                    listOf(obligation),
                    ValueFlowTerminal.Unresolved,
                    domain,
                    work(1),
                )
                .refined()
        val path =
            QueryImpactPath.fromEvidence(
                    site,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.Unresolved.Flow(obligation),
                )
                .refined()
        val ledger =
            QueryImpactLedger.fromEvidence(
                    listOf(site),
                    plan.source.domain,
                    plan.source.semantics,
                    emptyList(),
                    emptyList(),
                    listOf(observed),
                    listOf(path),
                    originalProducers = plan.source.producers,
                )
                .refined()
        val rows = QueryRows.ValuePaths.fromInvestigation(ledger).refined()
        return QueryResult(rows, emptyList())
    }

    private fun proof(request: ValueProducerSeedRequest): ValueProducerSeed {
        val call =
            ValueInvocation.fromCompiler(
                    RelationEndpoint.subject(request.enclosing),
                    request.anchor,
                    RelationEndpoint.subject(request.expectedCallable),
                )
                .refined()
        val site = ValueSite.fromCompiler(call.enclosing, request.anchor, ValueRole.ExpressionResult).refined()
        return ValueProducerSeed.fromCompiler(request, site, call).refined()
    }

    private fun document() =
        QueryImpactSourceDocument(
            bounded(listOf(QueryImpactProducerDocument(token(owner), token(callable), anchor(20, 35)))),
            bounded(emptyList()),
            QueryExpansionScopeDocument.Workspace,
            QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
            bounded(emptyList()),
        )

    private fun request(source: QueryImpactSourceDocument) =
        QueryRunRequest.Run(
            QueryFromDocument.Impact(source),
            bounded(emptyList()),
            QueryOutputDocument.ValuePaths,
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        )

    private fun token(endpoint: RelationEndpoint.Resolved): ProtocolText =
        (references.issueExact(SymbolSelector.issue(endpoint.lease, endpoint.scope, endpoint.evidence))
                as ExactSelectorIssuance.Issued)
            .selector

    private fun declaration(endpoint: RelationEndpoint.Resolved) =
        ImpactDeclarationReferenceDocument(
            ImpactSemanticBasisDocument.Published(
                text("/workspace"),
                ImpactEvidenceRevisionDocument.parse(7).refined(),
            ),
            text(endpoint.file.stableValue),
            anchor(endpoint.range.startInclusive, endpoint.range.endExclusive),
            text(endpoint.compilerIdentity.value),
        )

    private fun endpoint(name: String, start: Int, end: Int, fileName: String = "File.kt"): RelationEndpoint.Resolved {
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
                EvidenceGeneration.parse(7).refined(),
            )
        val file =
            (SymbolDiscoveryCandidate.fromBoundary(
                        SymbolDiscoveryKind.SYMBOL,
                        name,
                        lease,
                        Path.of("/workspace/$fileName"),
                        "file:///workspace/$fileName",
                        start,
                    )
                    .refined()
                    .location as SymbolDiscoveryCandidateLocation.Declaration)
                .file
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    start,
                    end,
                    name,
                    "fixture.$name",
                    CompilerSymbolKind.FUNCTION,
                    CanonicalCompilerSignature.function("fixture.$name", null, emptyList(), emptyList(), 0).refined(),
                )
                .refined()
        return RelationEndpoint.resolve(
                lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
                evidence,
            )
            .refined() as RelationEndpoint.Resolved
    }

    private fun anchor(start: Int, end: Int) =
        ImpactSourceRangeDocument(ProtocolOffset.parse(start).refined(), ProtocolOffset.parse(end).refined())

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).refined()

    private fun work(value: Long) = RelationWorkCount.parse(value).refined()

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
