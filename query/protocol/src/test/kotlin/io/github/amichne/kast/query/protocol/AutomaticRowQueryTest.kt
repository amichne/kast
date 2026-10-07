package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.QueryBindingNameDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryJoinModeDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.query.contract.QueryBindingName
import io.github.amichne.kast.query.contract.QueryBindingRow
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryJoinMode
import io.github.amichne.kast.query.contract.QueryOccurrence
import io.github.amichne.kast.query.contract.QueryRelationObservation
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QueryWalkArrival
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepth
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalRecord
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Scripted page observations prove runner composition, not native semantic execution. */
internal class AutomaticRowQueryTest : AutomaticSymbolQueryCase() {
    @Test
    fun `occurrences exhaust empty advancing pages and retained reads do not execute providers`() = runTest {
        val relation = RelationRequest.start(fixture.selector, RelationMeaning.References, fixture.budget)
        val fact =
            RelationFact.create(
                    relation,
                    relation.subject,
                    relation.subject,
                    RelationOccurrence.fromBoundary(relation.subject.file, 10, 11).refined(),
                    RelationProvenance.K2_AUTHORED_SOURCE,
                )
                .refined()
        val script =
            Script(listOf(listOf(row), emptyList(), listOf(row))) { rows, _ ->
                QueryResult(
                    QueryRows.Occurrences.of(
                        rows.map { QueryOccurrence.Declaration(it.copy(connections = listOf(fact)), fact) }
                    ),
                    emptyList(),
                )
            }
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol
                .executeAutomatically(
                    request.copy(output = QueryOutputDocument.Occurrences),
                    fixture.authority,
                    budget,
                    policy(1, retainedBytes = 128_000_000),
                )
                .also { assertInstanceOf(OperationOutcome.Complete::class.java, it, it.toString()) }
                as OperationOutcome.Complete
        assertEquals(2, result.evidence.payload.invocation!!.accumulatedRowCount)
        val ref = (result.evidence.payload.retention as QueryResultRetention.Retained).reference
        val tail =
            protocol.execute(
                QueryRunRequest.ReadResult.occurrences(ref, result.evidence.payload.nextCursor!!),
                fixture.authority,
                budget,
            ) as OperationOutcome.Complete
        assertEquals(1, tail.evidence.payload.items.values.size)
        assertInstanceOf(QueryResultItemDocument.Occurrence::class.java, tail.evidence.payload.items.values.single())
        script.assertDrained()
    }

    @Test
    fun `binding accumulation preserves both named cells and joined mode`() = runTest {
        val mode =
            QueryJoinMode.Inner.create(
                    QueryBindingName.parse("origin").refined(),
                    QueryBindingName.parse("target").refined(),
                )
                .refined()
        val binding = QueryBindingRow.join(mode, row, row).refined()
        val script =
            Script(listOf(listOf(row), emptyList(), listOf(row))) { rows, _ ->
                QueryResult(QueryRows.Bindings.of(rows.map { binding }, mode), emptyList())
            }
        val state = QueryStateStore()
        val joinedRequest = bindingRequest(state)
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references, state)
        val result =
            protocol
                .executeAutomatically(joinedRequest, fixture.authority, budget, policy(1, retainedBytes = 128_000_000))
                .also { assertInstanceOf(OperationOutcome.Complete::class.java, it, it.toString()) }
                as OperationOutcome.Complete
        assertEquals(2, result.evidence.payload.invocation!!.accumulatedRowCount)
        val reference = (result.evidence.payload.retention as QueryResultRetention.Retained).reference
        val tail =
            protocol.execute(
                QueryRunRequest.ReadResult.bindingRows(reference, result.evidence.payload.nextCursor!!),
                fixture.authority,
                budget,
            ) as OperationOutcome.Complete
        val cells = tail.evidence.payload.items.values.single() as QueryResultItemDocument.BindingRow
        assertEquals("origin", cells.left.name.value)
        assertEquals("target", cells.right.name.value)
        script.assertDrained()
    }

    @Test
    fun `changing binding mode across pages fails closed before merging`() = runTest {
        val script =
            Script(listOf(listOf(row), listOf(row))) { rows, page ->
                val mode =
                    QueryJoinMode.Inner.create(
                            QueryBindingName.parse("origin").refined(),
                            QueryBindingName.parse("target$page").refined(),
                        )
                        .refined()
                QueryResult(
                    QueryRows.Bindings.of(rows.map { QueryBindingRow.join(mode, it, it).refined() }, mode),
                    emptyList(),
                )
            }
        val state = QueryStateStore()
        val joinedRequest = bindingRequest(state)
        val result =
            CanonicalQueryProtocol(script.operations, fixture.references, state)
                .executeAutomatically(joinedRequest, fixture.authority, budget, policy(retainedBytes = 128_000_000))
                .also { assertInstanceOf(OperationOutcome.Qualified::class.java, it, it.toString()) }
                as OperationOutcome.Qualified
        assertEquals(QueryInvocationStop.INVALID_STATE, result.evidence.payload.invocation!!.stop)
        assertEquals(1, result.evidence.payload.invocation!!.accumulatedRowCount)
        script.assertDrained()
    }

    private fun bindingRequest(state: QueryStateStore): QueryRunRequest.Run {
        val complete =
            QueryExecutionResult.Complete.create(
                QueryResult(QueryRows.Symbols.of(listOf(row)), emptyList()),
                QueryCoverage.Complete(QueryCount.parse(1).refined()),
            )
        val issued =
            state.issueResult(request, QueryRetainedResult.capture(fixture.authority, complete).refined())
                as QueryResultIssuance.Issued
        return request.copy(
            output = QueryOutputDocument.BindingRows,
            steps =
                bounded(
                    listOf(
                        QueryStepDocument.Join(
                            QueryJoinModeDocument.Inner(
                                QueryBindingNameDocument.parse("origin").refined(),
                                QueryBindingNameDocument.parse("target").refined(),
                            ),
                            QueryFromDocument.Result(issued.reference),
                        )
                    )
                ),
        )
    }

    @Test
    fun `occurrence result and evidence cursors drain independently without semantic replay`() = runTest {
        val relation = RelationRequest.start(fixture.selector, RelationMeaning.References, fixture.budget)
        val fact = declarationFact(relation)
        val observation = completeObservation(relation)
        val script =
            Script(listOf(listOf(row), emptyList(), listOf(row))) { rows, _ ->
                QueryResult(
                    QueryRows.Occurrences.of(
                        rows.map { QueryOccurrence.Declaration(it.copy(connections = listOf(fact)), fact) }
                    ),
                    emptyList(),
                    relationObservations = listOf(observation),
                )
            }
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val complete =
            protocol.executeAutomatically(
                request.copy(output = QueryOutputDocument.Occurrences),
                fixture.authority,
                budget,
                policy(1, retainedBytes = 128_000_000),
            ) as OperationOutcome.Complete
        val payload = complete.evidence.payload
        val reference = (payload.retention as QueryResultRetention.Retained).reference
        assertEquals(3, payload.evidenceWindow!!.total.value)
        assertEquals(1, payload.relationObservations.values.size)
        val read =
            QueryRunRequest.ReadResult.occurrences(
                reference,
                QueryResultCursor.parse(2).refined(),
                evidenceCursor = payload.evidenceWindow!!.nextCursor,
            )
        val evidence = protocol.execute(read, fixture.authority, budget) as OperationOutcome.Complete
        assertTrue(evidence.evidence.payload.items.values.isEmpty())
        assertEquals(2, evidence.evidence.payload.relationObservations.values.size)
        assertNull(evidence.evidence.payload.evidenceWindow!!.nextCursor)
        assertEquals(evidence, protocol.execute(read, fixture.authority, budget))
        script.assertDrained()
    }

    @Test
    fun `traversal records keep arrival proof across empty advancing pages`() = runTest {
        val arrived = traversalSymbol()
        val script = Script(listOf(listOf(arrived), emptyList(), listOf(arrived)))
        val input =
            request.copy(
                output = QueryOutputDocument.TraversalRecords,
                steps =
                    bounded(
                        listOf(
                            QueryStepDocument.Walk(RelationKindDocument.REFERENCES, ProtocolCount.parse(1).refined())
                        )
                    ),
            )
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val complete =
            protocol.executeAutomatically(input, fixture.authority, budget, policy(1, retainedBytes = 128_000_000))
                as OperationOutcome.Complete
        assertEquals(2, complete.evidence.payload.invocation!!.accumulatedRowCount)
        val reference = (complete.evidence.payload.retention as QueryResultRetention.Retained).reference
        val read =
            protocol.execute(
                QueryRunRequest.ReadResult.traversalRecords(reference, complete.evidence.payload.nextCursor!!),
                fixture.authority,
                budget,
            ) as OperationOutcome.Complete
        val tail = read.evidence.payload.items.values.single() as QueryResultItemDocument.TraversalRecord
        assertEquals(1, tail.record.depth.value)
        script.assertDrained()
    }

    private fun declarationFact(relation: RelationRequest): RelationFact {
        return RelationFact.create(
                relation,
                relation.subject,
                relation.subject,
                RelationOccurrence.fromBoundary(relation.subject.file, 10, 11).refined(),
                RelationProvenance.K2_AUTHORED_SOURCE,
            )
            .refined()
    }

    private fun completeObservation(relation: RelationRequest): QueryRelationObservation {
        val batch =
            RelationBatch.create(
                    relation,
                    emptyList(),
                    RelationByteCount.parse(0).refined(),
                    RelationWorkCount.parse(0).refined(),
                    RelationResultCount.parse(0).refined(),
                )
                .refined()
        return QueryRelationObservation.from(
            RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
        )
    }

    private fun traversalSymbol(): QuerySymbol {
        val traversalBudget =
            TraversalBudget(
                budget.resources.resultLimit,
                TraversalByteLimit.parse(100_000).refined(),
                budget.resources.workUnitLimit,
                budget.resources.elapsedTimeLimit,
                TraversalDepthLimit.parse(1).refined(),
                TraversalFrontierLimit.parse(100).refined(),
                fixture.budget.copy(
                    resources = fixture.budget.resources.copy(resultLimit = budget.resources.resultLimit)
                ),
            )
        val plan = TraversalPlan.start(fixture.selector, RelationMeaning.References, traversalBudget).refined()
        val child = RelationRequest.start(fixture.selector, RelationMeaning.References, fixture.budget)
        val target =
            RelationEndpoint.resolve(
                    fixture.authority,
                    fixture.selector.scope,
                    CompilerGroundedSymbolEvidence.fromSelector(fixture.selector),
                )
                .refined()
        val fact =
            RelationFact.create(
                    child,
                    target,
                    child.subject,
                    RelationOccurrence.fromBoundary(child.subject.file, 10, 11).refined(),
                    RelationProvenance.K2_AUTHORED_SOURCE,
                )
                .refined()
        val record =
            TraversalRecord.create(plan, child.subject.fingerprint, TraversalDepth.parse(1).refined(), fact).refined()
        return row.copy(connections = listOf(fact), walkArrival = QueryWalkArrival.Proven.one(record))
    }
}
