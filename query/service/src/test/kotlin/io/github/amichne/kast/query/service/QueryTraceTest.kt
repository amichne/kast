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
import io.github.amichne.kast.symbol.contract.SymbolSelector
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
    fun `trace merges arrivals from multiple distinct seeds`() = runTest {
        val fixture = QueryServiceTest()
        val basis = fixture.selector(fixture.selection())
        val first = traceFunction(basis, "first", 30)
        val second = traceFunction(basis, "second", 60)
        val shared = traceFunction(basis, "shared", 90)
        val downstream = traceFunction(basis, "downstream", 120)
        for (seeds in listOf(listOf(first, second), listOf(second, first))) {
            for (grant in listOf(4L, 100L)) {
                val reads = mutableListOf<Pair<String, RelationMeaning>>()
                val service = traceService(fixture, multipleSeedRelations(first, shared, downstream, reads))
                val plan = fixture.exactReferencePlan(seeds, listOf(QueryStepSyntax.Trace()))
                val request = fixture.request(plan, grant, resultLimit = 100, returnedBytes = 1_000_000L)
                var result = service.run(request)
                var pages = 1
                while (result is QueryExecutionResult.Qualified) {
                    val checkpoint = assertInstanceOf(QueryContinuationState.Resumable::class.java, result.continuation)
                    assertTrue(++pages <= 20, "Both seeds must make bounded progress")
                    result =
                        service.run(
                            QueryExecutionRequest.create(plan, request.lease, request.budget, checkpoint.checkpoint)
                                .refined()
                        )
                }
                val rows = assertInstanceOf(QueryExecutionResult.Complete::class.java, result).result.symbolRows()
                assertEquals(
                    setOf(first.fingerprint, second.fingerprint, shared.fingerprint, downstream.fingerprint),
                    rows.map { it.selector.fingerprint }.toSet(),
                )
                val expected =
                    mapOf(
                        first.fingerprint to emptySet<Int>(),
                        second.fingerprint to emptySet(),
                        shared.fingerprint to setOf(101, 103, 107, 211, 223),
                        downstream.fingerprint to setOf(101, 103, 107, 211, 223, 307),
                    )
                rows.forEach { row ->
                    assertEquals(
                        expected.getValue(row.selector.fingerprint),
                        row.connections.map { it.occurrence.range.startInclusive }.toSet(),
                    )
                    assertTrue(row.connections.all { it.authority == first.lease.identity })
                }
                assertEquals(7, reads.size)
                assertEquals(1, reads.count { it == "shared" to RelationMeaning.Callers })
            }
        }
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

private fun multipleSeedRelations(
    first: SymbolSelector,
    shared: SymbolSelector,
    downstream: SymbolSelector,
    reads: MutableList<Pair<String, RelationMeaning>>,
): RelationOperations = RelationOperations { read ->
    reads += read.subject.name.value to read.meaning
    val targets =
        when (read.subject.name.value to read.meaning) {
            "first" to RelationMeaning.References -> listOf(shared to 101, shared to 103)
            "first" to RelationMeaning.Callers -> listOf(shared to 107)
            "second" to RelationMeaning.References -> listOf(shared to 211)
            "second" to RelationMeaning.Callers -> listOf(shared to 223)
            "first" to RelationMeaning.Overrides,
            "second" to RelationMeaning.Overrides -> emptyList()
            "shared" to RelationMeaning.Callers -> listOf(downstream to 307)
            else -> error("Unexpected read: ${read.subject.name.value} / ${read.meaning}")
        }
    val complete = RelationCompilation.complete(traceBatch(read, methodTraceFacts(read, first, targets)))
    RelationReadResult.Complete(complete.batch, complete.coverage)
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
