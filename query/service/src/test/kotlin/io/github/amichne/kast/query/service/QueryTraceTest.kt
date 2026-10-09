package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.ExactQueryStage
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
import io.github.amichne.kast.query.contract.QuerySymbol
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
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.file.Path
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
        val downstream = result.result.symbolRows().single { it.selector.fingerprint == client.fingerprint }
        assertEquals(
            setOf(200, 201, 210, 220, 230, 240),
            downstream.connections.map { it.occurrence.range.startInclusive }.toSet(),
        )
        assertFalse(reads.any { it.first == "client" }, "The fixed trace must not expand beyond the downstream layer")
        assertEquals(6, reads.size)
        assertEquals(
            listOf(
                "execute" to RelationMeaning.References,
                "execute" to RelationMeaning.Callers,
                "execute" to RelationMeaning.Overrides,
                "overrideExecute" to RelationMeaning.References,
                "overrideExecute" to RelationMeaning.Callers,
                "executeWithCache" to RelationMeaning.Callers,
            ),
            reads,
        )
        assertEquals(1, reads.count { it == "executeWithCache" to RelationMeaning.Callers })
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

    @Test
    fun `grouped method trace resumes without repeating downstream reads or losing paths`() = runTest {
        val fixture = QueryServiceTest()
        val basis = fixture.selector(fixture.selection())
        val symbols =
            MethodTraceSymbols(
                function(basis, "execute", 30),
                function(basis, "executeWithCache", 60),
                function(basis, "client", 90),
                function(basis, "overrideExecute", 120),
            )
        val plan = fixture.exactReferencePlan(listOf(symbols.seed), listOf(QueryStepSyntax.Trace()))
        for (grant in listOf(4L, 6L, 100L)) {
            val reads = mutableListOf<Pair<String, RelationMeaning>>()
            val service = methodService(fixture, symbols, reads)
            val request = fixture.request(plan, grant, resultLimit = 100, returnedBytes = 1_000_000L)
            var result = service.run(request)
            var pages = 1
            while (result is QueryExecutionResult.Qualified) {
                val checkpoint = assertInstanceOf(QueryContinuationState.Resumable::class.java, result.continuation)
                assertTrue(++pages <= 10, "The fixed trace must make bounded progress")
                result =
                    service.run(
                        QueryExecutionRequest.create(plan, request.lease, request.budget, checkpoint.checkpoint)
                            .refined()
                    )
            }
            val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, result)
            assertEquals(6, reads.size)
            assertEquals(1, reads.count { it == "executeWithCache" to RelationMeaning.Callers })
            assertEquals(
                setOf(200, 201, 210, 220, 230, 240),
                complete.result
                    .symbolRows()
                    .single { it.selector.fingerprint == symbols.client.fingerprint }
                    .connections
                    .map { it.occurrence.range.startInclusive }
                    .toSet(),
            )
            assertEquals(
                setOf("execute", "executeWithCache", "client", "overrideExecute"),
                complete.result.symbolRows().map { it.description.name.value }.toSet(),
            )
        }
    }

    @Test
    fun `grouping incoming paths cannot clear an incomplete upstream branch`() = runTest {
        val fixture = QueryServiceTest()
        val basis = fixture.selector(fixture.selection())
        val symbols =
            MethodTraceSymbols(
                function(basis, "execute", 30),
                function(basis, "executeWithCache", 60),
                function(basis, "client", 90),
                function(basis, "overrideExecute", 120),
            )
        val reads = mutableListOf<Pair<String, RelationMeaning>>()
        val plan = fixture.exactReferencePlan(listOf(symbols.seed), listOf(QueryStepSyntax.Trace()))
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                methodService(fixture, symbols, reads, incompleteReferences = true)
                    .run(fixture.request(plan, 100L, resultLimit = 100, returnedBytes = 1_000_000L)),
            )
        assertTrue(QueryLimitation.RELATION_INCOMPLETE in result.coverage.limitations)
        assertInstanceOf(QueryContinuationState.Terminal::class.java, result.continuation)
        assertEquals(6, reads.size)
        assertEquals(
            setOf(200, 201, 210, 220, 230, 240),
            result.result
                .symbolRows()
                .single { it.description.name.value == "client" }
                .connections
                .map { it.occurrence.range.startInclusive }
                .toSet(),
        )
    }

    @Test
    fun `native grouping retains distinct scoped capabilities for the same declaration`() {
        val fixture = QueryServiceTest()
        val first = function(fixture.selector(fixture.selection()), "executeWithCache", 60)
        val second =
            SymbolSelector.issue(
                first.lease,
                SymbolSearchScope.ExactFile(
                    CanonicalWorkspaceFilePath.fromCanonicalPath(
                            first.lease.workspaceRoot,
                            Path.of(first.file.stableValue),
                        )
                        .refined(),
                    first.scope.sourceKinds,
                    first.scope.generatedSources,
                ),
                CompilerGroundedSymbolEvidence.fromSelector(first),
                first.constraints,
            )
        val plan = fixture.exactReferencePlan(listOf(first), listOf(QueryStepSyntax.Trace()))
        val stage = traceUseStage((plan as AdmittedQueryPlan.ExactReferences).stage as ExactQueryStage.Trace)
        val rows = QueryIdentityRows(emptyMap())
        rows
            .acceptDistinct(
                stage,
                QuerySymbol(SymbolDescription.from(first), emptyList()),
            )
            .refined()
        rows
            .acceptDistinct(
                stage,
                QuerySymbol(SymbolDescription.from(second), emptyList()),
            )
            .refined()
        val reissued =
            SymbolSelector.issue(
                first.lease,
                first.scope,
                CompilerGroundedSymbolEvidence.fromSelector(first),
                first.constraints,
            )
        rows.acceptDistinct(stage, QuerySymbol(SymbolDescription.from(reissued), emptyList())).refined()
        assertEquals(listOf(first, second), QueryIdentityRows(rows.snapshot()).flushDistinct(stage).map { it.selector })
    }

    private fun methodService(
        fixture: QueryServiceTest,
        symbols: MethodTraceSymbols,
        reads: MutableList<Pair<String, RelationMeaning>>,
        incompleteReferences: Boolean = false,
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
                            val related =
                                RelationEndpoint.resolve(
                                        read.subject.lease,
                                        read.searchScope,
                                        CompilerGroundedSymbolEvidence.fromSelector(selected),
                                        read.searchConstraints,
                                    )
                                    .refined()
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
                if (
                    incompleteReferences &&
                        read.subject.name.value == "execute" &&
                        read.meaning == RelationMeaning.References
                ) {
                    val partial =
                        RelationCompilation.qualifiedTerminal(batch, setOf(RelationLimitation.UNSUPPORTED_ITEM))
                            .refined()
                    return@RelationOperations RelationReadResult.Qualified(partial.batch, partial.coverage)
                }
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
