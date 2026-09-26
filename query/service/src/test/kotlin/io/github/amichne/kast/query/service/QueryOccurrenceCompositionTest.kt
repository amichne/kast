package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryArrivalEvidence
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExactReferences
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOmissionEvidence
import io.github.amichne.kast.relation.contract.RelationOmissionMeasurement
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryOccurrenceCompositionTest {
    @Test
    fun `occurrence output keeps only the first callsite after distinct`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val service =
                queryService(
                    RelationOperations { read ->
                        assertEquals(RelationSearchBoundary.WORKSPACE_EXPANSION, read.boundary)
                        val starts = if (read.meaning == RelationMeaning.Callees) listOf(100, 102) else listOf(200, 202)
                        val facts = starts.map { relationFact(read, it) }
                        val batch = relationBatch(read, facts)
                        RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
                    }
                )
            val plan =
                admittedPlan(
                    QuerySourceSyntax.ExactReferences(QueryExactReferences.from(listOf(selected)).refined()),
                    listOf(
                        QueryStepSyntax.Related(RelationMeaning.Callees),
                        QueryStepSyntax.Related(RelationMeaning.Callers),
                        QueryStepSyntax.Distinct,
                    ),
                    QueryOutputSyntax.Occurrences,
                )
            val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(request(plan, 8L)))
            assertEquals(1, complete.result.symbolRows().size)
            assertEquals(
                listOf(200),
                complete.result.symbolRows().map { row ->
                    (row.arrival as QueryArrivalEvidence.Proven).facts.single().occurrence.range.startInclusive
                },
            )
            assertTrue(complete.result.symbolRows().all { it.connections.size == 2 })
        }
    }

    @Test
    fun `terminal relation omissions remain attributed and qualified through retention`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val evidence = mutableListOf<RelationOmissionEvidence>()
            val service =
                queryService(
                    RelationOperations { read ->
                        val fact = relationFact(read, 100)
                        val omission =
                            RelationOmissionEvidence.fromObservedPage(
                                read.providerCursor.provider,
                                RelationLimitation.UNSUPPORTED_ITEM,
                                RelationOmissionMeasurement.ObservedOnPage(RelationWorkCount.parse(2).refined()),
                                listOf(fact.occurrence),
                            )
                        evidence += omission
                        val batch = relationBatch(read, listOf(fact)).withOmissions(listOf(omission)).refined()
                        RelationReadResult.Qualified(
                            batch,
                            RelationIncompleteCoverage.terminal(batch, setOf(RelationLimitation.UNSUPPORTED_ITEM))
                                .refined(),
                        )
                    }
                )
            val plan =
                admittedPlan(
                    QuerySourceSyntax.ExactReferences(QueryExactReferences.from(listOf(selected)).refined()),
                    listOf(QueryStepSyntax.Related(RelationMeaning.Callees)),
                    QueryOutputSyntax.Occurrences,
                )
            val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(request(plan, 8L)))
            assertEquals(1, first.result.symbolRows().size)
            assertEquals(listOf(QueryLimitation.RELATION_INCOMPLETE), first.coverage.limitations)
            assertEquals(selected, first.result.omissions.single().subject)
            assertEquals(RelationMeaning.Callees, first.result.omissions.single().meaning)
            assertEquals(evidence.single(), first.result.omissions.single().evidence)

            val retained = QueryRetainedResult.capture(selected.lease, first).refined().symbolsResult()
            val suffix = admittedPlan(QuerySourceSyntax.Retained(retained), emptyList(), QueryOutputSyntax.Occurrences)
            val composed =
                assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(request(suffix, 8L)))
            assertEquals(1, composed.result.symbolRows().size)
            assertEquals(first.result.omissions, composed.result.omissions)
            assertEquals(listOf(QueryLimitation.RELATION_INCOMPLETE), composed.coverage.limitations)
        }
    }

    @Test
    fun `occurrence output fanout resumes from retained evidence without replaying relation work`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            var relationCalls = 0
            val service =
                queryService(
                    RelationOperations { read ->
                        relationCalls++
                        val batch = relationBatch(read, listOf(relationFact(read, 100), relationFact(read, 102)))
                        RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
                    }
                )
            val prefix =
                admittedPlan(
                    QuerySourceSyntax.ExactReferences(QueryExactReferences.from(listOf(selected)).refined()),
                    listOf(QueryStepSyntax.Related(RelationMeaning.Callees)),
                    QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
                )
            val prefixResult =
                assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(request(prefix, 8L)))
            assertEquals(2, prefixResult.result.symbolRows().size)
            assertEquals(1, (prefixResult.result.symbolRows().first().arrival as QueryArrivalEvidence.Proven).facts.size)
            val retained = QueryRetainedResult.capture(selected.lease, prefixResult).refined().symbolsResult()
            assertArrivalImmutable(retained)
            val suffix = admittedPlan(QuerySourceSyntax.Retained(retained), emptyList(), QueryOutputSyntax.Occurrences)
            val firstRequest = request(suffix, 8L, resultLimit = 1)
            val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(firstRequest))
            val checkpoint = assertInstanceOf(QueryContinuationState.Resumable::class.java, first.continuation)
            val last =
                assertInstanceOf(
                    QueryExecutionResult.Complete::class.java,
                    service.run(resume(firstRequest, checkpoint)),
                )
            assertEquals(
                listOf(100, 102),
                (first.result.symbolRows() + last.result.symbolRows()).map {
                    (it.arrival as QueryArrivalEvidence.Proven).facts.single().occurrence.range.startInclusive
                },
            )
            assertEquals(1, relationCalls)
        }
    }

    @Test
    fun `positive occurrence precedes terminal omission under one result slot`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val service = queryService(RelationOperations { read -> terminalOmission(read) })
            val plan = occurrencePlan(selected)
            val firstRequest = request(plan, 8L, resultLimit = 1)
            val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(firstRequest))
            assertEquals(1, first.result.symbolRows().size)
            assertTrue(first.result.omissions.isEmpty())
            assertTrue(QueryLimitation.RELATION_INCOMPLETE in first.coverage.limitations)
            val retained = QueryRetainedResult.capture(selected.lease, first).refined().symbolsResult()
            assertEquals(1, retained.symbols.size)
            val checkpoint = assertInstanceOf(QueryContinuationState.Resumable::class.java, first.continuation)
            val last = service.run(resume(firstRequest, checkpoint))
            val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, last)
            assertTrue(qualified.result.symbolRows().isEmpty())
            assertEquals(RelationLimitation.UNSUPPORTED_ITEM, qualified.result.omissions.single().evidence.reason)
        }
    }

    @Test
    fun `measured resumable omission remains qualified after page cursor completes`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            var relationCalls = 0
            val service =
                queryService(
                    RelationOperations { read ->
                        relationCalls++
                        if (read.providerCursor.nextPosition.value == 0L) measuredPageOmission(read)
                        else {
                            val batch = relationBatch(read, emptyList())
                            RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
                        }
                    }
                )
            val firstRequest = request(occurrencePlan(selected), 8L, resultLimit = 2)
            val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(firstRequest))
            assertEquals(1, first.result.symbolRows().size)
            assertEquals(RelationLimitation.RESULT_LIMIT_REACHED, first.result.omissions.single().evidence.reason)
            assertTrue(QueryLimitation.RELATION_INCOMPLETE in first.coverage.limitations)
            val retained = QueryRetainedResult.capture(selected.lease, first).refined().symbolsResult()
            assertEquals(first.result.omissions, retained.omissions)
            val checkpoint = assertInstanceOf(QueryContinuationState.Resumable::class.java, first.continuation)
            val last =
                assertInstanceOf(
                    QueryExecutionResult.Qualified::class.java,
                    service.run(resume(firstRequest, checkpoint)),
                )
            assertTrue(QueryLimitation.RELATION_INCOMPLETE in last.coverage.limitations)
            assertEquals(2, relationCalls)
        }
    }
}

private fun QueryServiceTest.occurrencePlan(selected: io.github.amichne.kast.symbol.contract.SymbolSelector) =
    admittedPlan(
        QuerySourceSyntax.ExactReferences(QueryExactReferences.from(listOf(selected)).refined()),
        listOf(QueryStepSyntax.Related(RelationMeaning.Callees)),
        QueryOutputSyntax.Occurrences,
    )

private fun resume(
    first: QueryExecutionRequest,
    checkpoint: QueryContinuationState.Resumable,
): QueryExecutionRequest =
    QueryExecutionRequest.create(first.plan, first.lease, first.budget, checkpoint.checkpoint).refined()

private fun terminalOmission(read: RelationRequest): RelationReadResult.Qualified {
    val fact = relationFact(read, 100)
    val omission =
        RelationOmissionEvidence.fromObservedPage(
            read.providerCursor.provider,
            RelationLimitation.UNSUPPORTED_ITEM,
            RelationOmissionMeasurement.UnmeasuredOnPage,
            emptyList(),
        )
    val batch = relationBatch(read, listOf(fact)).withOmissions(listOf(omission)).refined()
    return RelationReadResult.Qualified(
        batch,
        RelationIncompleteCoverage.terminal(batch, setOf(RelationLimitation.UNSUPPORTED_ITEM)).refined(),
    )
}

private fun measuredPageOmission(read: RelationRequest): RelationReadResult.Qualified {
    val fact = relationFact(read, 100)
    val omission =
        RelationOmissionEvidence.fromObservedPage(
            read.providerCursor.provider,
            RelationLimitation.RESULT_LIMIT_REACHED,
            RelationOmissionMeasurement.ObservedOnPage(RelationWorkCount.parse(1).refined()),
            listOf(fact.occurrence),
        )
    val batch = relationBatch(read, listOf(fact)).withOmissions(listOf(omission)).refined()
    val next = read.providerCursor.advance(RelationProviderItemDescriptor.parse(fact.canonicalProjection()).refined())
    return RelationReadResult.Qualified(
        batch,
        RelationIncompleteCoverage.resumable(batch, setOf(RelationLimitation.RESULT_LIMIT_REACHED), next).refined(),
    )
}

private fun assertArrivalImmutable(retained: QueryRetainedResult.Symbols) {
    val arrival = retained.symbols.first().arrival as QueryArrivalEvidence.Proven
    assertThrows(UnsupportedOperationException::class.java) {
        (arrival.facts as MutableList<RelationFact>).clear()
    }
    assertEquals(1, (retained.symbols.first().arrival as QueryArrivalEvidence.Proven).facts.size)
}

private fun QueryServiceTest.queryService(relations: RelationOperations): QueryService =
    QueryService(
        discoveryEmpty(false),
        exactOperations(
            describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
            resolve = { error("No discovery expected") },
        ),
        SourceReadOperations { error("No source expected") },
        relations,
        unexpectedQueryTraversal(),
        queryTestTraversalCeiling(),
    )

private fun relationFact(read: RelationRequest, start: Int): RelationFact =
    RelationFact.create(
            read,
            read.subject,
            read.subject,
            RelationOccurrence.fromBoundary(read.subject.file, start, start + 1).refined(),
            RelationProvenance.K2_AUTHORED_SOURCE,
        )
        .refined()

private fun relationBatch(read: RelationRequest, facts: List<RelationFact>): RelationBatch =
    RelationBatch.create(
            read,
            facts,
            RelationByteCount.parse(facts.sumOf { it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() })
                .refined(),
            RelationWorkCount.parse(facts.size.toLong()).refined(),
            RelationResultCount.parse(facts.size).refined(),
        )
        .refined()

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }
