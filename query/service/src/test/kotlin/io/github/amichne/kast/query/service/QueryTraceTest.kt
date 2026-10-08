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
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolSelector
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
            service(
                fixture,
                RelationOperations { read ->
                    requested += read.meaning
                    val starts = if (read.meaning == RelationMeaning.References) listOf(100, 102) else emptyList()
                    val facts = starts.map { fact(read, it) }
                    val batch = batch(read, facts)
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
            service(
                fixture,
                RelationOperations { read ->
                    assertEquals(boundary, read.boundary)
                    val batch = batch(read, listOf(fact(read, 100)))
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
    fun `method trace preserves adapter paths overrides and occurrence evidence at fixed depth`() = runTest {
        val fixture = QueryServiceTest()
        val basis = fixture.selector(fixture.selection())
        val seed = function(basis, "execute", 30)
        val adapter = function(basis, "executeWithCache", 60)
        val client = function(basis, "client", 90)
        val override = function(basis, "overrideExecute", 120)
        val reads = mutableListOf<Pair<String, RelationMeaning>>()
        val service = methodService(fixture, MethodTraceSymbols(seed, adapter, client, override), reads)
        val plan = fixture.exactReferencePlan(listOf(seed), listOf(QueryStepSyntax.Trace()))
        val execution = service.run(fixture.request(plan, 100L, resultLimit = 100, returnedBytes = 1_000_000L))
        val result =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                execution,
                "Unexpected qualifications: ${(execution as? QueryExecutionResult.Qualified)?.coverage?.limitations}",
            )
        assertEquals(
            setOf("execute", "executeWithCache", "client", "overrideExecute"),
            result.result.symbolRows().map { it.description.name.value }.toSet(),
        )
        val downstream = result.result.symbolRows().single { it.selector == client }
        assertEquals(
            setOf(200, 201, 210, 220, 230, 240),
            downstream.connections.map { it.occurrence.range.startInclusive }.toSet(),
        )
        assertFalse(reads.any { it.first == "client" }, "The fixed trace must not expand beyond the downstream layer")
        assertEquals(9, reads.size)
        assertTrue(
            result.result.symbolRows().all { row -> row.connections.all { it.authority == seed.lease.identity } }
        )
    }

    private data class MethodTraceSymbols(
        val seed: SymbolSelector,
        val adapter: SymbolSelector,
        val client: SymbolSelector,
        val override: SymbolSelector,
    )

    private fun methodService(
        fixture: QueryServiceTest,
        symbols: MethodTraceSymbols,
        reads: MutableList<Pair<String, RelationMeaning>>,
    ): QueryService {
        val seed = symbols.seed
        val adapter = symbols.adapter
        val client = symbols.client
        val override = symbols.override
        return service(
            fixture,
            RelationOperations { read ->
                reads += read.subject.name.value to read.meaning
                val targets =
                    when (read.subject.name.value to read.meaning) {
                        "execute" to RelationMeaning.References -> listOf(adapter to 200, adapter to 201)
                        "execute" to RelationMeaning.Overrides -> listOf(override to 210)
                        "execute" to RelationMeaning.Callers -> listOf(adapter to 220)
                        "overrideExecute" to RelationMeaning.References -> emptyList()
                        "overrideExecute" to RelationMeaning.Callers -> listOf(adapter to 230)
                        "executeWithCache" to RelationMeaning.Callers -> listOf(client to 240)
                        else -> error("Unexpected trace read: ${read.subject.name.value} / ${read.meaning}")
                    }
                val facts =
                    targets
                        .map { (selected, offset) ->
                            val related = RelationEndpoint.subject(selected)
                            RelationFact.create(
                                    read,
                                    related,
                                    read.subject,
                                    RelationOccurrence.fromBoundary(seed.file, offset, offset + 1).refined(),
                                    RelationProvenance.K2_AUTHORED_SOURCE,
                                )
                                .refined()
                        }
                        .sorted()
                val batch = batch(read, facts)
                val completed = RelationCompilation.complete(batch)
                RelationReadResult.Complete(completed.batch, completed.coverage)
            },
        )
    }

    @Test
    fun `trace obeys one work grant across branches and retains continuation`() = runTest {
        val fixture = QueryServiceTest()
        val seed = fixture.selector(fixture.selection())
        val reads = mutableListOf<RelationMeaning>()
        val service =
            service(
                fixture,
                RelationOperations { read ->
                    reads += read.meaning
                    val batch = batch(read, emptyList())
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

    private fun function(
        basis: io.github.amichne.kast.symbol.contract.SymbolSelector,
        name: String,
        offset: Int,
    ): io.github.amichne.kast.symbol.contract.SymbolSelector {
        val signature =
            io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature.function(
                    "sample.$name",
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                )
                .refined()
        val evidence =
            io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence.fromBoundary(
                    basis.file,
                    offset,
                    offset + 20,
                    name,
                    "sample.$name",
                    io.github.amichne.kast.symbol.contract.CompilerSymbolKind.FUNCTION,
                    signature,
                )
                .refined()
        return io.github.amichne.kast.symbol.contract.SymbolSelector.issue(
            basis.lease,
            basis.scope,
            evidence,
            basis.constraints,
        )
    }

    private fun service(fixture: QueryServiceTest, relations: RelationOperations) =
        QueryService(
            fixture.discoveryEmpty(false),
            fixture.exactOperations(
                describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                resolve = { error("No discovery expected") },
            ),
            SourceReadOperations { request ->
                traceMemberSourceRead(
                    (request.anchor as io.github.amichne.kast.source.contract.SourceReadAnchor.Symbol).selector,
                    emptyList(),
                )
            },
            relations,
            unexpectedQueryTraversal(),
            queryTestTraversalCeiling(),
        )

    private fun fact(read: RelationRequest, start: Int) =
        RelationFact.create(
                read,
                read.subject,
                read.subject,
                RelationOccurrence.fromBoundary(read.subject.file, start, start + 1).refined(),
                RelationProvenance.K2_AUTHORED_SOURCE,
            )
            .refined()

    private fun batch(read: RelationRequest, facts: List<RelationFact>) =
        RelationBatch.create(
                read,
                facts,
                RelationByteCount.parse(
                        facts.sumOf { it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() }
                    )
                    .refined(),
                RelationWorkCount.parse(facts.size.toLong()).refined(),
                RelationResultCount.parse(facts.size).refined(),
            )
            .refined()
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
