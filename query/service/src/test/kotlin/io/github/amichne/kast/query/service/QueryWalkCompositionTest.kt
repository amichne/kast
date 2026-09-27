package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExactReferences
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QueryWalkArrival
import io.github.amichne.kast.query.contract.QueryWalkCoverage
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationEndpointFingerprint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.traversal.contract.TraversalCheckpoint
import io.github.amichne.kast.traversal.contract.TraversalContinuation
import io.github.amichne.kast.traversal.contract.TraversalDepth
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierEntry
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalNode
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPage
import io.github.amichne.kast.traversal.contract.TraversalPendingState
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalPosition
import io.github.amichne.kast.traversal.contract.TraversalProgress
import io.github.amichne.kast.traversal.contract.TraversalRecord
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.traversal.contract.TraversalStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryWalkCompositionTest {
    @Test
    fun `empty walk remains a complete retainable result with progress`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val service = walkService(traversal = { TraversalResult.complete(page(it, emptyList(), 1)) })
            val complete =
                assertInstanceOf(
                    QueryExecutionResult.Complete::class.java,
                    service.run(request(walkPlan(selected), 20, resultLimit = 1)),
                )
            assertTrue(complete.result.symbolRows().isEmpty())
            assertEquals(1, complete.result.walkObservations.single().expandedFrontier.value)
            val retained = QueryRetainedResult.capture(selected.lease, complete).refined().symbolsResult()
            assertTrue(retained.symbols.isEmpty())
            assertEquals(complete.result.walkObservations, retained.walkObservations)
        }
    }

    @Test
    fun `walk distinct retains first compiler occurrence and page observation`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val plan = walkPlan(selected, distinct = true)
            var calls = 0
            val service =
                walkService(
                    traversal = { traversalPlan ->
                        calls++
                        assertEquals(TraversalPosition.Start, traversalPlan.position)
                        assertEquals(3, traversalPlan.budget.depth.value)
                        assertEquals(TraversalStrategy.BreadthFirst, traversalPlan.strategy)
                        val records = listOf(walkRecord(traversalPlan, 100), walkRecord(traversalPlan, 101))
                        TraversalResult.complete(page(traversalPlan, records, 1))
                    }
                )
            val complete =
                assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(request(plan, 20, 3)))
            assertEquals(1, calls)
            assertEquals(1, complete.result.symbolRows().size)
            val occurrences =
                complete.result.symbolRows().map { (it.walkArrival as QueryWalkArrival.Proven).records.single() }
            assertEquals(listOf(100), occurrences.map { it.fact.occurrence.range.startInclusive })
            assertEquals(1, occurrences[0].depth.value)
            val observation = complete.result.walkObservations.single()
            assertEquals(selected.fingerprint, observation.subject.fingerprint)
            assertEquals(1, observation.expandedFrontier.value)
            assertEquals(2, observation.progress.totalEdges)
            assertTrue(complete.result.failures.isEmpty())
        }
    }

    @Test
    fun `walk resumes child cursor without revalidating completed prefix`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            var descriptions = 0
            var calls = 0
            val service =
                walkService(
                    traversal = { traversalPlan ->
                        calls++
                        if (calls == 1) {
                            val record = walkRecord(traversalPlan, 100)
                            val firstPage = page(traversalPlan, listOf(record), 1)
                            TraversalResult.qualifiedResumable(
                                    firstPage,
                                    setOf(TraversalLimitation.RECORD_LIMIT_REACHED),
                                    emptySet(),
                                    continuation(traversalPlan, record),
                                )
                                .refined()
                        } else {
                            assertInstanceOf(TraversalPosition.Resume::class.java, traversalPlan.position)
                            TraversalResult.complete(page(traversalPlan, emptyList(), 2, totalEdges = 1))
                        }
                    },
                    describe = {
                        descriptions++
                        SymbolDescriptionResult.Described(SymbolDescription.from(it))
                    },
                )
            val initial = request(walkPlan(selected), 20, 2)
            val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(initial))
            assertEquals(1, first.result.symbolRows().size)
            assertEquals(1, first.result.walkObservations.size)
            assertEquals(1, calls)
            assertEquals(1, descriptions)
            val retained = QueryRetainedResult.capture(selected.lease, first).refined()
            assertEquals(first.result.walkObservations, retained.walkObservations)
            val cursor = assertInstanceOf(QueryContinuationState.Resumable::class.java, first.continuation)
            assertImmutableWalkSnapshot(retained, cursor)
            val resumed =
                QueryExecutionRequest.create(initial.plan, initial.lease, initial.budget, cursor.checkpoint).refined()
            val last = assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(resumed))
            assertTrue(last.result.symbolRows().isEmpty())
            assertEquals(1, last.result.walkObservations.size)
            assertEquals(2, calls)
            assertEquals(1, descriptions)
        }
    }

    @Test
    fun `walk depth above host ceiling rejects before semantic work`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val service =
                walkService({ error("Traversal must not start") }, describe = { error("Prefix must not run") })
            val rejected = service.run(request(walkPlan(selected, depth = 9), 20))
            assertEquals(
                QueryExecutionResult.Rejected(QueryExecutionRejection.BUDGET_REJECTED),
                rejected,
            )
        }
    }

    @Test
    fun `walk rejects a same-basis child page from a different plan`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val service =
                walkService(
                    traversal = { requested ->
                        val wrong =
                            TraversalPlan.start(
                                    requested.start,
                                    RelationMeaning.Callees,
                                    requested.budget,
                                    requested.strategy,
                                )
                                .refined()
                        TraversalResult.complete(page(wrong, emptyList(), 1))
                    }
                )
            val rejected = service.run(request(walkPlan(selected), 20))
            assertEquals(
                QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION),
                rejected,
            )
        }
    }

    @Test
    fun `qualified timeout retains positive record and progress on the same page`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            var now = 0L
            val service =
                walkService(
                    traversal = { plan ->
                        val record = walkRecord(plan, 100)
                        now = 2_000_000L
                        TraversalResult.qualifiedTerminal(
                                page(plan, listOf(record), 1),
                                setOf(TraversalLimitation.TIME_LIMIT_REACHED),
                                emptySet(),
                            )
                            .refined()
                    },
                    clock = QueryNanoClock { now },
                )
            val qualified =
                assertInstanceOf(
                    QueryExecutionResult.Qualified::class.java,
                    service.run(request(walkPlan(selected), 20, resultLimit = 2, elapsedMillis = 1)),
                )
            assertEquals(1, qualified.result.symbolRows().size)
            assertEquals(1, qualified.result.walkObservations.size)
            assertEquals(1, qualified.result.walkObservations.single().progress.totalEdges)
            assertTrue(QueryLimitation.TRAVERSAL_INCOMPLETE in qualified.coverage.limitations)
        }
    }
}

private fun assertImmutableWalkSnapshot(retained: QueryRetainedResult, cursor: QueryContinuationState.Resumable) {
    val coverage =
        assertInstanceOf(QueryWalkCoverage.Resumable::class.java, retained.walkObservations.single().coverage)
    assertThrows(UnsupportedOperationException::class.java) {
        (coverage.limitations as MutableList<TraversalLimitation>).clear()
    }
    val pending = (cursor.checkpoint as PipelineCheckpoint).tasks.filterIsInstance<PipelineTask.Walk>().single()
    val child = requireNotNull(pending.cursor)
    assertThrows(UnsupportedOperationException::class.java) {
        (child.checkpoint.frontier as MutableList<TraversalFrontierEntry>).clear()
    }
    assertThrows(UnsupportedOperationException::class.java) {
        (child.checkpoint.visited as MutableSet<RelationEndpointFingerprint>).clear()
    }
}

private fun QueryServiceTest.walkPlan(selected: SymbolSelector, depth: Int = 3, distinct: Boolean = false) =
    admittedPlan(
        QuerySourceSyntax.ExactReferences(QueryExactReferences.from(listOf(selected)).refined()),
        listOf(
            QueryStepSyntax.Walk(
                RelationMeaning.Callers,
                TraversalDepthLimit.parse(depth).refined(),
                TraversalStrategy.BreadthFirst,
            )
        ) + if (distinct) listOf(QueryStepSyntax.Distinct) else emptyList(),
        QueryOutputSyntax.TraversalRecords,
    )

private fun QueryServiceTest.walkService(
    traversal: suspend (TraversalPlan) -> TraversalResult,
    describe: (SymbolSelector) -> SymbolDescriptionResult = {
        SymbolDescriptionResult.Described(SymbolDescription.from(it))
    },
    clock: QueryNanoClock = QueryNanoClock { 0L },
): QueryService =
    QueryService(
        discoveryEmpty(false),
        exactOperations(describe = describe, resolve = { error("Discovery was not expected") }),
        SourceReadOperations { error("Source read was not expected") },
        RelationOperations { error("One-hop relation must remain traversal-owned") },
        TraversalOperations(traversal),
        queryTestTraversalCeiling(),
        clock = clock,
    )

private fun walkRecord(plan: TraversalPlan, offset: Int): TraversalRecord {
    val selected = plan.start
    val evidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                selected.file,
                500,
                520,
                "Related",
                "sample.Related",
                selected.kind,
                CanonicalCompilerSignature.classLike("sample.Related").refined(),
            )
            .refined()
    val related = RelationEndpoint.resolve(selected.lease, selected.scope, evidence, selected.constraints).refined()
    val read =
        RelationRequest.start(selected, plan.meaning, plan.budget.oneHop, RelationSearchBoundary.WORKSPACE_EXPANSION)
    val fact =
        RelationFact.create(
                read,
                related,
                read.subject,
                RelationOccurrence.fromBoundary(selected.file, offset, offset + 1).refined(),
                RelationProvenance.K2_AUTHORED_SOURCE,
            )
            .refined()
    return TraversalRecord.create(
            plan,
            RelationEndpoint.subject(selected).fingerprint,
            TraversalDepth.parse(1).refined(),
            fact,
        )
        .refined()
}

private fun page(
    plan: TraversalPlan,
    records: List<TraversalRecord>,
    sequence: Long,
    totalEdges: Long = records.size.toLong(),
): TraversalPage =
    TraversalPage.fromBoundary(
            plan,
            records,
            records.sumOf { it.fact.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() },
            examinedWorkUnits = 1,
            elapsedMillis = 1,
            expandedFrontier = 1,
            progress =
                TraversalProgress.restore(sequence, sequence, totalEdges, if (totalEdges == 0L) 0 else 1).refined(),
        )
        .refined()

private fun continuation(plan: TraversalPlan, record: TraversalRecord): TraversalContinuation {
    val node = TraversalNode.related(plan, record.related).refined()
    val frontier = TraversalFrontierEntry.create(plan, node, record.depth).refined()
    val progress = TraversalProgress.restore(1, 1, 1, 1).refined()
    val checkpoint =
        TraversalCheckpoint.create(
                plan,
                listOf(frontier),
                setOf(RelationEndpoint.subject(plan.start).fingerprint),
                TraversalPendingState.None,
                progress = progress,
            )
            .refined()
    return TraversalContinuation.issue(plan, checkpoint).refined()
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined test value: $failure")
    }
