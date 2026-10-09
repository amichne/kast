package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingStatusDocument
import io.github.amichne.kast.protocol.contract.ImpactModelDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactRequiredObligationDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.validateImpactAccounting
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.service.QueryNanoClock
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalOperations
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real canonical admission, query interpreter and retained projections with only native observations scripted. */
class QueryImpactPeerCanonicalRetentionTest {
    @Test
    fun `peer path and completed proof remain qualified and expandable without compiler replay`() = runTest {
        val f = QueryImpactPeerSourceAdmissionTest.Fixture()
        val run = request(f)
        val budget = budget(f)
        val attempts = mutableListOf<ValueSite>()
        val flow = flow(f, attempts)
        val native = f.source.native()
        val protocol =
            CanonicalQueryProtocol(
                service(flow, budget),
                f.source.references,
                producerSeeds = native,
                peerSiteAdmissions = listOf(f.proof),
            )
        val initial = protocol.executePage(run, f.source.owner.lease, budget)
        assertTrue(
            initial is OperationOutcome.Qualified,
            "Uninvestigated peer flow must retain an unresolved question: $initial",
        )
        val payload = (initial as OperationOutcome.Qualified).evidence.payload
        assertEquals(
            1,
            payload.items.values.size,
            "qualification=${initial.qualification}; accounting=${payload.impactAccounting}",
        )
        val path = (payload.items.values.single() as QueryResultItemDocument.ValuePath).path
        assertTrue(path.terminal is ImpactPathTerminalDocument.UnresolvedPeerContinuation)
        val closure =
            ((payload.impactAccounting as ImpactAccountingDocument.Investigated).status
                    as ImpactAccountingStatusDocument.Unresolved)
                .required
                .values
        assertTrue(ImpactRequiredObligationDocument.BOUNDARY in closure)
        val reference = (payload.retention as QueryResultRetention.Retained).reference
        val repeated =
            protocol.executePage(QueryRunRequest.ReadResult.valuePaths(reference), f.source.owner.lease, budget)
        assertTrue(repeated is OperationOutcome.Qualified)
        val retained = (repeated as OperationOutcome.Qualified).evidence.payload
        assertEquals(payload.question, retained.question)
        assertEquals(payload.items.values, retained.items.values)
        assertRetainedWitnesses(protocol, f, budget, payload)
        assertEquals(1, attempts.size)
        assertEquals(listOf(97L, 94L, 93L), native.grants)
        assertEquals(listOf(f.source.range(20, 35)), native.positions)
    }

    private fun request(f: QueryImpactPeerSourceAdmissionTest.Fixture): QueryRunRequest.Run {
        val boundary = f.document.models.values.single() as ImpactModelDocument.Boundary
        val document =
            f.document.copy(
                models = bounded(listOf(boundary.copy(rules = bounded(listOf(boundary.rules.values.first())))))
            )
        return QueryRunRequest.Run(
            QueryFromDocument.Impact(document),
            bounded(emptyList()),
            QueryOutputDocument.ValuePaths,
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            retention = QueryRetentionModeDocument.RETAIN,
        )
    }

    private fun budget(f: QueryImpactPeerSourceAdmissionTest.Fixture): QueryBudget {
        return f.source.budget.copy(
            resources = f.source.budget.resources.copy(workUnitLimit = WorkUnitLimit.parse(100).value()),
            checkpointBytes = QueryByteLimit.parse(1_000_000).value(),
            returnedBytes = QueryByteLimit.parse(1_000_000).value(),
        )
    }

    private fun flow(
        f: QueryImpactPeerSourceAdmissionTest.Fixture,
        attempts: MutableList<ValueSite>,
    ): ValueFlowCompilerPort {
        return ValueFlowCompilerPort { input ->
            attempts += input.source
            assertEquals(1, attempts.size, "No peer or replay flow call is permitted")
            assertEquals(f.source.owner.lease, input.source.enclosing.lease)
            val obligation = ValueFlowObligation(input.source, ValueFlowUnsupportedCause.UNMODELED_CALL)
            val selector = SymbolSelector.issue(f.source.owner.lease, f.source.owner.scope, f.source.owner.evidence)
            val domain = RelationRequest.start(selector, RelationMeaning.References, input.budget, input.boundary)
            ValueFlowRead.Observed(
                ValueFlowStep.fromCompiler(
                        input.source,
                        emptyList(),
                        listOf(obligation),
                        ValueFlowTerminal.Unresolved,
                        domain,
                        RelationWorkCount.parse(1).value(),
                    )
                    .value()
            )
        }
    }

    private suspend fun assertRetainedWitnesses(
        protocol: CanonicalQueryProtocol,
        f: QueryImpactPeerSourceAdmissionTest.Fixture,
        budget: QueryBudget,
        payload: QueryRunResult,
    ) {
        val reference = (payload.retention as QueryResultRetention.Retained).reference
        val path = (payload.items.values.single() as QueryResultItemDocument.ValuePath).path
        for (section in listOf(ImpactWitnessSectionDocument.MODELS, ImpactWitnessSectionDocument.NATIVE_READS)) {
            val witnesses =
                protocol.executePage(
                    QueryRunRequest.ReadResult.impactWitness(reference, section),
                    f.source.owner.lease,
                    budget,
                )
            assertTrue(witnesses is OperationOutcome.Qualified)
            val page = (witnesses as OperationOutcome.Qualified).evidence.payload
            assertEquals(payload.question, page.question)
            assertEquals(Refinement.Refined(Unit), page.validateImpactAccounting())
            if (section == ImpactWitnessSectionDocument.MODELS) {
                val proof =
                    ((page.items.values.single() as QueryResultItemDocument.ImpactWitness).item.witness
                        as ImpactWitnessDocument.PeerBoundaryModel)
                assertEquals(
                    (path.terminal as ImpactPathTerminalDocument.UnresolvedPeerContinuation).admission,
                    proof.admission,
                )
            }
        }
    }

    private fun service(flow: ValueFlowCompilerPort, budget: QueryBudget) =
        QueryService(
            SymbolDiscoveryOperations { error("Unexpected discovery") },
            object : SymbolExactOperations {
                override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionResult =
                    error("Unexpected exact resolution")

                override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionResult =
                    error("Unexpected exact description")
            },
            SourceReadOperations { error("Unexpected source read") },
            RelationOperations { error("Unexpected relation read") },
            TraversalOperations { error("Unexpected traversal") },
            TraversalBudget(
                budget.resources.resultLimit,
                TraversalByteLimit.parse(100000).value(),
                budget.resources.workUnitLimit,
                budget.resources.elapsedTimeLimit,
                TraversalDepthLimit.parse(1).value(),
                TraversalFrontierLimit.parse(1).value(),
                RelationBudget(budget.resources, RelationByteLimit.parse(100000).value()),
            ),
            QueryNanoClock { 0 },
            flow,
        )

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

    private fun <V, F> Refinement<V, F>.value(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }
}
