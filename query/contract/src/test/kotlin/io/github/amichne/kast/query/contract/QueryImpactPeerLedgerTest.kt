package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundaryArrival
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.BoundaryUnresolvedReason
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class QueryImpactPeerLedgerTest {
    @Test
    fun `guarded peer endpoint is conserved without an invented peer native read`() {
        val f = QueryImpactPeerTestFixture()
        val path = peerPath(f)
        val ledger = ledger(f, listOf(path)).peerValue()
        assertEquals(listOf(f.server.producer), ledger.observations.map { it.source })
        assertEquals(
            setOf(QueryImpactRequiredObligation.BOUNDARY),
            assertInstanceOf<QueryImpactClosure.Unresolved>(ledger.closure).required,
        )
        val rows = QueryRows.ValuePaths.fromInvestigation(ledger).peerValue()
        assertEquals(
            QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION),
            QueryExecutionResult.Complete.create(
                QueryResult(rows, emptyList()),
                QueryCoverage.Complete(QueryCount.parse(1).peerValue()),
            ),
        )
    }

    @Test
    fun `ordinary downstream boundary cannot borrow completed peer endpoint exception`() {
        val f = QueryImpactPeerTestFixture()
        val connection = BoundaryArrival.connect(f.sourcePosition, f.model).peerValue()
        val end =
            QueryImpactTerminal.Unresolved.Boundary(
                BoundaryArrival.unresolved(f.targetPosition, BoundaryUnresolvedReason.MISSING_MODEL)
            )
        val path =
            QueryImpactPath.fromEvidence(
                    f.server.producer,
                    listOf(QueryImpactStep.ModeledBoundary(connection)),
                    QueryImpactRepresentation.NotModeled,
                    end,
                )
                .peerValue()
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.MISSING_NATIVE_OBSERVATION), ledger(f, listOf(path)))
    }

    @Test
    fun `peer terminal requires its original registered wrapper`() {
        val f = QueryImpactPeerTestFixture()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.UNPROVEN_TERMINAL),
            ledger(f, listOf(peerPath(f)), peers = emptyList()),
        )
    }

    @Test
    fun `peer endpoint cannot replace a source compiler sibling`() {
        val f = QueryImpactPeerTestFixture()
        val paths = f.server.paths + peerPath(f)
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH),
            ledger(f, paths.drop(1), observations = f.server.observations),
        )
        assertEquals(3, ledger(f, paths, observations = f.server.observations).peerValue().paths.size)
    }

    @Test
    fun `peer endpoint cannot discharge an independent mutable source obligation`() {
        val f = QueryImpactPeerTestFixture()
        val obligation = ValueFlowObligation(f.server.producer, ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW)
        val observed = f.server.observe(f.server.producer, emptyList(), listOf(obligation))
        val peer = peerPath(f)
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_OBLIGATION),
            ledger(f, listOf(peer), observations = listOf(observed)),
        )
        val mutable =
            QueryImpactPath.fromEvidence(
                    f.server.producer,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.Unresolved.Flow(obligation),
                )
                .peerValue()
        val closure = ledger(f, listOf(peer, mutable), observations = listOf(observed)).peerValue().closure
        assertEquals(
            setOf(QueryImpactRequiredObligation.BOUNDARY, QueryImpactRequiredObligation.NATIVE_FLOW),
            assertInstanceOf<QueryImpactClosure.Unresolved>(closure).required,
        )
    }

    @Test
    fun `independent peer native observations cannot merge into source investigation ledger`() {
        val f = QueryImpactPeerTestFixture()
        val domain =
            RelationRequest.start(
                f.request.enclosing,
                RelationMeaning.References,
                f.receipt.grant,
                f.server.domain.boundary,
            )
        val peerRead =
            ValueFlowStep.fromCompiler(
                    f.site,
                    emptyList(),
                    emptyList(),
                    ValueFlowTerminal.SupportedDomainExhausted,
                    domain,
                    RelationWorkCount.parse(1).peerValue(),
                )
                .peerValue()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.FOREIGN_PEER_BASIS),
            ledger(
                f,
                listOf(peerPath(f)),
                observations = listOf(f.server.observe(f.server.producer, emptyList()), peerRead),
            ),
        )
    }

    @Test
    fun `retained source rows preserve exact peer proof and cannot be captured under peer authority`() {
        val f = QueryImpactPeerTestFixture()
        val original = ledger(f, listOf(peerPath(f))).peerValue()
        val rows = QueryRows.ValuePaths.fromInvestigation(original).peerValue()
        val result =
            QueryExecutionResult.Qualified(
                QueryResult(rows, emptyList()),
                QueryCoverage.Qualified.create(
                        QueryCount.parse(1).peerValue(),
                        setOf(QueryLimitation.RELATION_INCOMPLETE),
                    )
                    .peerValue(),
            )
        val retained =
            assertInstanceOf<QueryRetainedResult.ValuePaths>(
                QueryRetainedResult.capture(f.server.lease, result).peerValue()
            )
        val selected = retained.selectRows(listOf(0)).peerValue()
        val ledger = assertInstanceOf<QueryValuePathAccounting.Investigated>(selected.rows.accounting).ledger
        assertSame(original, ledger)
        assertSame(f.receipt, ledger.peerBoundaries.single().target.acquisition)
        assertEquals(
            Refinement.Rejected(QueryRetainedResultFailure.BASIS_MISMATCH),
            QueryRetainedResult.capture(f.peerLease, result),
        )
    }

    @Test
    fun `unused peer model retains admission in its original model ordinal without creating a route`() {
        val f = QueryImpactPeerTestFixture()
        val source =
            BoundaryPosition.at(
                f.server.destination,
                f.sourcePosition.kind,
                f.sourcePosition.contract,
                f.sourcePosition.slot,
            )
        val model =
            BoundaryModel.Continuation.admit(
                    f.model.reference,
                    source.reference,
                    f.targetPosition.reference,
                    source,
                    f.targetPosition,
                    emptySet(),
                )
                .peerValue()
        val peer = QueryImpactPeerBoundary.admit(f.server.lease, model, f.admission).peerValue()
        val observed = f.server.observe(f.server.producer, emptyList())
        val path =
            QueryImpactPath.fromEvidence(
                    f.server.producer,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.SupportedDomainEnd.admit(observed).peerValue(),
                )
                .peerValue()
        val ledger = ledger(f, listOf(path), listOf(peer), listOf(observed)).peerValue()
        assertEquals(QueryImpactClosure.Discharged, ledger.closure)
        assertEquals(listOf(path), ledger.paths)
        val view = QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.MODELS, 0, 1).peerValue()
        assertEquals(0, view.entries.single().ordinal.value)
        assertSame(
            peer,
            assertInstanceOf<QueryImpactWitnessEntry.PeerBoundaryModel>(view.entries.single().evidence).boundary,
        )
    }

    private fun peerPath(f: QueryImpactPeerTestFixture): QueryImpactPath {
        val connection = BoundaryArrival.connect(f.sourcePosition, f.model).peerValue()
        return QueryImpactPath.fromEvidence(
                f.server.producer,
                listOf(QueryImpactStep.ModeledBoundary(connection)),
                QueryImpactRepresentation.NotModeled,
                QueryImpactTerminal.Unresolved.PeerContinuation.admit(f.boundary, connection).peerValue(),
            )
            .peerValue()
    }

    private fun ledger(
        f: QueryImpactPeerTestFixture,
        paths: List<QueryImpactPath>,
        peers: List<QueryImpactPeerBoundary> = listOf(f.boundary),
        observations: List<ValueFlowStep> = listOf(f.server.observe(f.server.producer, emptyList())),
    ) =
        QueryImpactLedger.fromEvidence(
            listOf(f.server.producer),
            f.server.domain.boundary,
            QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
            emptyList(),
            peers.map { it.model }.ifEmpty { listOf(f.model) },
            observations,
            paths,
            originalProducers = listOf(f.server.producerWitness),
            peerBoundaries = peers,
        )
}
