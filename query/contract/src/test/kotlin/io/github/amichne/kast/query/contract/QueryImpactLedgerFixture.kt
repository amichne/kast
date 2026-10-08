package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
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

internal class QueryImpactLedgerFixture {
    val lease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/fixture")).value(),
            EvidenceGeneration.parse(1).value(),
        )
    val scope =
        SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            SymbolGeneratedSourcePolicy.EXCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )
    val file =
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
    val evidence =
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
    val owner = RelationEndpoint.resolve(lease, scope, evidence).value()
    val domain =
        RelationRequest.start(
            SymbolSelector.issue(lease, scope, evidence),
            RelationMeaning.References,
            RelationBudget(
                ResourceBudget(
                    ResultLimit.parse(20).value(),
                    WorkUnitLimit.parse(100).value(),
                    ElapsedTimeLimitMillis.parse(1000).value(),
                ),
                RelationByteLimit.parse(100000).value(),
            ),
            RelationSearchBoundary.WORKSPACE_EXPANSION,
        )
    val producer = site(10, ValueRole.ExpressionResult)
    val producerWitness =
        QueryImpactProducer.admit(
                producer,
                ValueInvocation.fromCompiler(owner, producer.range, owner).value(),
            )
            .value()
    val first = site(20, ValueRole.LocalBinding)
    val second = site(30, ValueRole.LocalBinding)
    val destination = site(40, ValueRole.LocalRead)
    val edges =
        listOf(
            edge(producer, first, ValueTransferKind.LOCAL_BINDING),
            edge(producer, second, ValueTransferKind.LOCAL_BINDING),
            edge(first, destination, ValueTransferKind.LOCAL_READ),
            edge(second, destination, ValueTransferKind.LOCAL_READ),
        )
    val observations =
        listOf(
            observe(producer, edges.take(2)),
            observe(first, listOf(edges[2])),
            observe(second, listOf(edges[3])),
            observe(destination, emptyList()),
        )
    val terminal = QueryImpactTerminal.SupportedDomainEnd.admit(observations.last()).value()
    val paths =
        listOf(listOf(edges[0], edges[2]), listOf(edges[1], edges[3])).map { chain ->
            QueryImpactPath.fromEvidence(
                    producer,
                    chain.map(QueryImpactStep::Compiler),
                    QueryImpactRepresentation.NotModeled,
                    terminal,
                )
                .value()
        }

    fun ledger(paths: List<QueryImpactPath>, observations: List<ValueFlowStep> = this.observations) =
        QueryImpactLedger.fromEvidence(
            listOf(producer),
            domain.boundary,
            QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
            emptyList(),
            emptyList(),
            observations,
            paths,
            originalProducers = listOf(producerWitness),
        )

    fun observe(
        source: ValueSite,
        edges: List<ValueTransfer>,
        obligations: List<ValueFlowObligation> = emptyList(),
    ) =
        ValueFlowStep.fromCompiler(
                source,
                edges,
                obligations,
                if (obligations.isEmpty()) ValueFlowTerminal.SupportedDomainExhausted else ValueFlowTerminal.Unresolved,
                domain,
                RelationWorkCount.parse(1).value(),
            )
            .value()

    private fun site(offset: Int, role: ValueRole) =
        ValueSite.fromCompiler(owner, ExactDeclarationTextRange.parse(offset, offset + 1).value(), role).value()

    private fun edge(from: ValueSite, to: ValueSite, kind: ValueTransferKind) =
        ValueTransfer.fromCompiler(from, to, kind).value()
}

private fun <T, F> Refinement<T, F>.value(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
