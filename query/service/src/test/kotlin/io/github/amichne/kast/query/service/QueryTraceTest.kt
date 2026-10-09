package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExactReferences
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryPlanAdmission
import io.github.amichne.kast.query.contract.QueryPlanAdmissionFailure
import io.github.amichne.kast.query.contract.QueryPlanCompiler
import io.github.amichne.kast.query.contract.QueryPlanSyntax
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryTraceTest {
    @Test
    fun `trace groups symbols while retaining every independent occurrence`() = runTest {
        val fixture = QueryServiceTest()
        val seed = fixture.selector(fixture.selection())
        val requested = mutableListOf<RelationMeaning>()
        val service =
            traceService(
                fixture,
                RelationOperations { read ->
                    requested += read.meaning
                    val starts = if (read.meaning == RelationMeaning.References) listOf(100, 102) else emptyList()
                    val facts = starts.map { traceFact(read, it) }
                    val batch = traceBatch(read, facts)
                    val completed = RelationCompilation.complete(batch)
                    RelationReadResult.Complete(completed.batch, completed.coverage)
                },
            )
        val plan = fixture.exactReferencePlan(listOf(seed), listOf(QueryStepSyntax.Trace()))
        val result =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                service.run(fixture.request(plan, 100L, resultLimit = 100)),
            )
        assertEquals(listOf(RelationMeaning.References, RelationMeaning.Implementations), requested)
        val grouped = result.result.symbolRows().single()
        assertEquals(seed, grouped.selector)
        assertEquals(listOf(100, 102), grouped.connections.map { it.occurrence.range.startInclusive })
        assertEquals(2, result.result.relationObservations.size)
    }

    @Test
    fun `trace retains incomplete scope and cannot manufacture exhaustive coverage`() = runTest {
        val fixture = QueryServiceTest()
        val seed = fixture.selector(fixture.selection())
        val boundary = RelationSearchBoundary.RETAINED_SUBJECT
        val service =
            traceService(
                fixture,
                RelationOperations { read ->
                    assertEquals(boundary, read.boundary)
                    val batch = traceBatch(read, listOf(traceFact(read, 100)))
                    val partial =
                        RelationCompilation.qualifiedTerminal(batch, setOf(RelationLimitation.BYTE_LIMIT_REACHED))
                            .refined()
                    RelationReadResult.Qualified(partial.batch, partial.coverage)
                },
            )
        val plan = fixture.exactReferencePlan(listOf(seed), listOf(QueryStepSyntax.Trace(boundary)))
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                service.run(fixture.request(plan, 100L, resultLimit = 100)),
            )
        assertTrue(QueryLimitation.RELATION_INCOMPLETE in result.coverage.limitations)
        assertTrue(result.result.relationObservations.all { it.question.requestedDomain == boundary })
        assertTrue(result.result.symbolRows().single().connections.isNotEmpty())
    }

    @Test
    fun `trace requires terminal grouped symbol output`() {
        val fixture = QueryServiceTest()
        val source =
            QuerySourceSyntax.ExactReferences(
                QueryExactReferences.from(listOf(fixture.selector(fixture.selection()))).refined()
            )
        for ((steps, output) in
            listOf(
                listOf(QueryStepSyntax.Trace(), QueryStepSyntax.Distinct) to
                    QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
                listOf(QueryStepSyntax.Trace()) to QueryOutputSyntax.Occurrences,
            )) {
            val result =
                assertInstanceOf(
                    QueryPlanAdmission.Rejected::class.java,
                    QueryPlanCompiler.admit(QueryPlanSyntax(source, steps, output)),
                )
            assertEquals(QueryPlanAdmissionFailure.OutputTypeMismatch, result.failure)
        }
    }

    @Test
    fun `trace obeys one work grant across branches and retains continuation`() = runTest {
        val fixture = QueryServiceTest()
        val seed = fixture.selector(fixture.selection())
        val reads = mutableListOf<RelationMeaning>()
        val service =
            traceService(
                fixture,
                RelationOperations { read ->
                    reads += read.meaning
                    val batch = traceBatch(read, emptyList())
                    val complete = RelationCompilation.complete(batch)
                    RelationReadResult.Complete(complete.batch, complete.coverage)
                },
            )
        val plan = fixture.exactReferencePlan(listOf(seed), listOf(QueryStepSyntax.Trace()))
        val first = fixture.request(plan, 2L, resultLimit = 100)
        val result = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(first))
        assertEquals(listOf(RelationMeaning.References), reads)
        assertTrue(QueryLimitation.WORK_LIMIT_REACHED in result.coverage.limitations)
        val checkpoint = assertInstanceOf(QueryContinuationState.Resumable::class.java, result.continuation)
        val resumed =
            QueryExecutionRequest.create(
                    plan,
                    first.lease,
                    fixture.request(plan, 8L, resultLimit = 100).budget,
                    checkpoint.checkpoint,
                )
                .refined()
        val last = assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(resumed))
        assertEquals(listOf(RelationMeaning.References, RelationMeaning.Implementations), reads)
        assertEquals(seed, last.result.symbolRows().single().selector)
    }
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
