package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.query.contract.QueryImpactRequestedSite
import io.github.amichne.kast.query.contract.QueryImpactSource
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryPlanAdmission
import io.github.amichne.kast.query.contract.QueryPlanCompiler
import io.github.amichne.kast.query.contract.QueryPlanSyntax
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.ExactModelCallablePosition
import io.github.amichne.kast.relation.contract.ModelCallableReference
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

internal data class ImpactReadExpectation(
    val site: ValueSite,
    val edges: List<ValueTransfer> = emptyList(),
    val causes: List<ValueFlowUnsupportedCause> = emptyList(),
    val work: Long = 1,
)

internal class QueryImpactExecutionFixture(generation: Long = 1) {
    val lease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/fixture")).value(),
            EvidenceGeneration.parse(generation).value(),
        )
    val scope =
        SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            SymbolGeneratedSourcePolicy.EXCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )
    private val file =
        (SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.SYMBOL,
                    "owner",
                    lease,
                    Path.of("/fixture/File.kt"),
                    "file:///fixture/File.kt",
                    0,
                )
                .value()
                .location as SymbolDiscoveryCandidateLocation.Declaration)
            .file
    private val proof =
        CompilerGroundedSymbolEvidence.fromBoundary(
                file,
                0,
                200,
                "owner",
                "fixture.owner",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function("fixture.owner", null, emptyList(), emptyList(), 0).value(),
            )
            .value()
    val owner = RelationEndpoint.resolve(lease, scope, proof).value()
    private val invocation =
        ValueInvocation.fromCompiler(owner, ExactDeclarationTextRange.parse(10, 20).value(), owner).value()
    val producer = QueryImpactProducer.admit(invocation.resultSite(), invocation).value()
    val plan = plan()

    fun plan(
        models: List<RepresentationRule> = emptyList(),
        boundaries: List<BoundaryModel> = emptyList(),
        domain: RelationSearchBoundary = RelationSearchBoundary.WORKSPACE_EXPANSION,
        requestedSites: List<QueryImpactRequestedSite> = emptyList(),
        peerBoundaries: List<io.github.amichne.kast.query.contract.QueryImpactPeerBoundary> = emptyList(),
    ): AdmittedQueryPlan {
        val source =
            QueryImpactSource.admit(
                    listOf(producer),
                    models,
                    boundaries,
                    domain,
                    requestedSites = requestedSites,
                    peerBoundaries = peerBoundaries,
                )
                .value()
        return (QueryPlanCompiler.admit(
                QueryPlanSyntax(QuerySourceSyntax.Impact(source), emptyList(), QueryOutputSyntax.ValuePaths)
            ) as QueryPlanAdmission.Admitted)
            .plan
    }

    fun call(name: String, start: Int, end: Int): ValueInvocation {
        val proof =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    210,
                    220,
                    name,
                    "fixture.$name",
                    CompilerSymbolKind.FUNCTION,
                    CanonicalCompilerSignature.function(
                            "fixture.$name",
                            null,
                            emptyList(),
                            listOf("kotlin.String"),
                            0,
                        )
                        .value(),
                )
                .value()
        val endpoint = RelationEndpoint.resolve(lease, scope, proof).value()
        return ValueInvocation.fromCompiler(owner, ExactDeclarationTextRange.parse(start, end).value(), endpoint)
            .value()
    }

    fun argument(call: ValueInvocation) =
        ValueSite.fromCompiler(
                owner,
                ExactDeclarationTextRange.parse(call.range.startInclusive + 1, call.range.endExclusive - 1).value(),
                ValueRole.Argument(call, ValueArgumentPosition.parse(0).value()),
            )
            .value()

    fun bind(endpoint: RelationEndpoint, position: ModelValuePosition) =
        ExactModelCallablePosition.admit(
                ModelCallableReference(
                    endpoint.lease.identity,
                    endpoint.compilerIdentity,
                    endpoint.file,
                    endpoint.range,
                    position,
                ),
                RevalidatedRelationEndpoint.validate(endpoint, (endpoint as RelationEndpoint.Resolved).evidence)
                    .value(),
            )
            .value()

    fun site(start: Int, role: ValueRole) =
        ValueSite.fromCompiler(owner, ExactDeclarationTextRange.parse(start, start + 1).value(), role).value()

    fun edge(a: ValueSite, b: ValueSite, kind: ValueTransferKind) = ValueTransfer.fromCompiler(a, b, kind).value()

    fun request(
        work: Long = 100,
        results: Int = 20,
        checkpoint: QueryCheckpoint? = null,
        plan: AdmittedQueryPlan = this.plan,
        checkpointBytes: Long = 10000000,
    ) =
        QueryExecutionRequest.create(
                plan,
                lease,
                QueryBudget(
                    ResourceBudget(
                        ResultLimit.parse(results).value(),
                        WorkUnitLimit.parse(work).value(),
                        ElapsedTimeLimitMillis.parse(10000).value(),
                    ),
                    QueryByteLimit.parse(10000000).value(),
                    QueryByteLimit.parse(checkpointBytes).value(),
                ),
                checkpoint,
            )
            .value()

    fun service(
        port: ValueFlowCompilerPort,
        presentation: io.github.amichne.kast.query.contract.QueryPresentationExecution? = null,
    ) =
        QueryService(
            SymbolDiscoveryOperations { error("Unexpected discovery") },
            QueryServiceTest().exactOperations { error("Unexpected exact read") },
            SourceReadOperations { error("Unexpected source read") },
            RelationOperations { error("Unexpected relation read") },
            unexpectedQueryTraversal(),
            queryTestTraversalCeiling(),
            QueryNanoClock { 0 },
            port,
            presentation,
        )

    fun script(reads: List<ImpactReadExpectation>) = Script(reads)

    inner class Script(scripted: List<ImpactReadExpectation>) {
        private val expected = ArrayDeque(scripted)
        val examined = mutableListOf<ValueSite>()
        val grants = mutableListOf<RelationBudget>()
        val port = ValueFlowCompilerPort { input ->
            check(expected.isNotEmpty()) { "Excess native read" }
            val read = expected.removeFirst()
            assertEquals(read.site, input.source)
            examined += input.source
            grants += input.budget
            val obligations = read.causes.map { ValueFlowObligation(read.site, it) }
            val domain =
                RelationRequest.start(
                    SymbolSelector.issue(lease, scope, proof),
                    RelationMeaning.References,
                    input.budget,
                    input.boundary,
                )
            ValueFlowRead.Observed(
                ValueFlowStep.fromCompiler(
                        read.site,
                        read.edges,
                        obligations,
                        if (obligations.isEmpty()) ValueFlowTerminal.SupportedDomainExhausted
                        else ValueFlowTerminal.Unresolved,
                        domain,
                        RelationWorkCount.parse(read.work).value(),
                    )
                    .value()
            )
        }

        fun assertConsumed() {
            assertTrue(expected.isEmpty(), "Unconsumed native observations")
        }
    }
}

private fun <V> Refinement<V, *>.value(): V = (this as Refinement.Refined).value
