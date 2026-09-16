package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolExactRejection
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryPaginationTest {
    @Test
    fun `intermediate discovery bytes do not spend final output authority`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val service =
                service(
                    discovery = discoveryWithCandidate(),
                    exact =
                        exactOperations { candidate ->
                            SymbolResolutionResult.Resolved(
                                io.github.amichne.kast.symbol.contract.ResolvedSymbol(selector(candidate))
                            )
                        },
                )
            val bytes =
                io.github.amichne.kast.query.contract
                    .QuerySymbol(SymbolDescription.from(selected), emptyList())
                    .projectedUtf8Size()
            val result = service.run(request(symbolPlan(), workLimit = 8L, returnedBytes = bytes))
            assertEquals(1, result.symbolCount())
        }
    }

    @Test
    fun `byte pages retain the exact unconsumed item without redoing effects`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            var descriptions = 0
            val service =
                service(
                    exact =
                        exactOperations(
                            describe = {
                                descriptions++
                                SymbolDescriptionResult.Described(SymbolDescription.from(it))
                            },
                            resolve = { error("No discovery expected") },
                        )
                )
            val size =
                io.github.amichne.kast.query.contract
                    .QuerySymbol(SymbolDescription.from(selected), emptyList())
                    .projectedUtf8Size()
            val first = request(exactReferencePlan(List(2) { selected }), workLimit = 8L, returnedBytes = size)
            val page = service.run(first) as QueryExecutionResult.Qualified
            assertEquals(1, page.symbolCount())
            val checkpoint =
                (page.continuation as io.github.amichne.kast.query.contract.QueryContinuationState.Resumable).checkpoint
            val last =
                service.run(QueryExecutionRequest.create(first.plan, first.lease, first.budget, checkpoint).refined())
            assertInstanceOf(QueryExecutionResult.Complete::class.java, last)
            assertEquals(1, last.symbolCount())
            assertEquals(2, descriptions)
        }
    }

    @Test
    fun `upstream work limitation survives a resumed downstream page`() = runTest {
        QueryServiceTest().apply {
            val discovery = discoveryWithCandidate()
            val service =
                service(
                    discovery =
                        SymbolDiscoveryOperations { input ->
                            val complete =
                                (discovery.discover(input) as SymbolDiscoveryResult.Discovered).outcome
                                    as SymbolDiscoveryOutcome.Complete
                            SymbolDiscoveryResult.Discovered(
                                SymbolDiscoveryOutcome.Qualified(
                                    complete.batch,
                                    SymbolDiscoveryQualifications.from(
                                            setOf(SymbolDiscoveryQualification.WORK_LIMIT_REACHED)
                                        )
                                        .refined(),
                                )
                            )
                        },
                    exact =
                        exactOperations {
                            SymbolResolutionResult.Resolved(
                                io.github.amichne.kast.symbol.contract.ResolvedSymbol(selector(it))
                            )
                        },
                )
            val first = request(symbolPlan(), workLimit = 1L)
            val page = service.run(first) as QueryExecutionResult.Qualified
            val checkpoint =
                (page.continuation as io.github.amichne.kast.query.contract.QueryContinuationState.Resumable).checkpoint
            val resumed = request(first.plan, workLimit = 8L)
            val last =
                service.run(QueryExecutionRequest.create(first.plan, first.lease, resumed.budget, checkpoint).refined())
                    as QueryExecutionResult.Qualified
            assertEquals(1, last.symbolCount())
            assertTrue(QueryLimitation.DISCOVERY_INCOMPLETE in last.coverage.limitations)
            assertTrue(QueryLimitation.WORK_LIMIT_REACHED in last.coverage.limitations)
            assertEquals(
                io.github.amichne.kast.query.contract.QueryContinuationState.Terminal(
                    io.github.amichne.kast.query.contract.QueryTerminalReason.UPSTREAM_INCOMPLETE
                ),
                last.continuation,
            )
        }
    }

    @Test
    fun `relation cursor and distinct state survive a full output page`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val positions = mutableListOf<Long>()
            val service =
                QueryService(
                    discoveryEmpty(false),
                    exactOperations(
                        describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                        resolve = { error("No discovery expected") },
                    ),
                    SourceReadOperations { error("No source expected") },
                    relationPages(selected, positions),
                )
            val first =
                request(
                    exactReferencePlan(
                        listOf(selected),
                        listOf(QueryStepSyntax.Related(RelationMeaning.Callees), QueryStepSyntax.Distinct),
                    ),
                    workLimit = 8L,
                    resultLimit = 1,
                )
            val page = service.run(first) as QueryExecutionResult.Qualified
            assertEquals(1, page.symbolCount())
            val checkpoint =
                (page.continuation as io.github.amichne.kast.query.contract.QueryContinuationState.Resumable).checkpoint
            val last =
                service.run(QueryExecutionRequest.create(first.plan, first.lease, first.budget, checkpoint).refined())
            assertInstanceOf(QueryExecutionResult.Complete::class.java, last)
            assertEquals(0, last.symbolCount())
            assertEquals(listOf(0L, 1L), positions)
        }
    }

    @Test
    fun `failure byte stops retain pending evidence and an oversized item is terminal`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val service =
                service(
                    exact =
                        exactOperations(
                            describe = { SymbolDescriptionResult.Rejected(SymbolExactRejection.AMBIGUOUS_DECLARATION) },
                            resolve = { error("No discovery expected") },
                        )
                )
            val first = request(exactReferencePlan(listOf(selected)), workLimit = 8L, returnedBytes = 1L)
            val page = service.run(first) as QueryExecutionResult.Qualified
            assertEquals(
                io.github.amichne.kast.query.contract.QueryContinuationState.Terminal(
                    io.github.amichne.kast.query.contract.QueryTerminalReason.OUTPUT_ITEM_TOO_LARGE
                ),
                page.continuation,
            )
            assertTrue(QueryLimitation.REFINEMENT_INCOMPLETE in page.coverage.limitations)
            assertTrue(QueryLimitation.BYTE_LIMIT_REACHED in page.coverage.limitations)
        }
    }

    @Test
    fun `work and item pages resume ordered references without omission`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            var descriptions = 0
            val service =
                service(
                    exact =
                        exactOperations(
                            describe = {
                                descriptions++
                                SymbolDescriptionResult.Described(SymbolDescription.from(it))
                            },
                            resolve = { error("No discovery expected") },
                        )
                )
            val first = request(exactReferencePlan(List(3) { selected }), workLimit = 1L, resultLimit = 1)
            var pageRequest = first
            var count = 0
            var pages = 0
            while (true) {
                val page = service.run(pageRequest)
                count += page.symbolCount()
                pages++
                check(pages <= 3)
                if (page is QueryExecutionResult.Complete) break
                val qualified = page as QueryExecutionResult.Qualified
                val continuation =
                    assertInstanceOf(
                        io.github.amichne.kast.query.contract.QueryContinuationState.Resumable::class.java,
                        qualified.continuation,
                    )
                pageRequest =
                    QueryExecutionRequest.create(first.plan, first.lease, first.budget, continuation.checkpoint)
                        .refined()
            }
            assertEquals(3, count)
            assertEquals(3, descriptions)
            assertEquals(3, pages)
        }
    }

    @Test
    fun `distinct retains seen state across pages`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val service =
                service(
                    exact =
                        exactOperations(
                            describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                            resolve = { error("No discovery expected") },
                        )
                )
            val first =
                request(
                    exactReferencePlan(List(3) { selected }, listOf(QueryStepSyntax.Distinct)),
                    workLimit = 8L,
                    resultLimit = 1,
                )
            val page = service.run(first) as QueryExecutionResult.Qualified
            assertEquals(1, page.symbolCount())
            val continuation =
                page.continuation as io.github.amichne.kast.query.contract.QueryContinuationState.Resumable
            val last =
                service.run(
                    QueryExecutionRequest.create(first.plan, first.lease, first.budget, continuation.checkpoint)
                        .refined()
                )
            assertInstanceOf(QueryExecutionResult.Complete::class.java, last)
            assertEquals(0, last.symbolCount())
        }
    }

    @Test
    fun `terminal qualified edges continue through non leaf and leaf expansions`() = runTest {
        for (leaf in listOf(false, true)) {
            QueryServiceTest().apply {
                val selected = selector(selection())
                val reads = mutableListOf<Pair<String, Long>>()
                val service = recordingService(selected, reads, leaf = leaf)
                val result = service.run(request(twoHopPlan(selected), workLimit = 100L))
                val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, result)
                assertEquals(listOf("PaymentService" to 0L, "B" to 0L), reads)
                assertEquals(listOf(QueryLimitation.RELATION_INCOMPLETE), qualified.coverage.limitations)
                assertEquals(
                    io.github.amichne.kast.query.contract.QueryContinuationState.Terminal(
                        io.github.amichne.kast.query.contract.QueryTerminalReason.UPSTREAM_INCOMPLETE),
                    qualified.continuation,
                )
                val symbols = (qualified.result.items as io.github.amichne.kast.query.contract.QueryResultSet.Symbols).values
                assertEquals(if (leaf) emptyList() else listOf("C"), symbols.map { it.description.name.value }.sorted())
                symbols.forEach { symbol ->
                    assertEquals(listOf("PaymentService", "B"), symbol.connections.map { it.source.name.value })
                    assertEquals(listOf("B", symbol.description.name.value), symbol.connections.map { it.target.name.value })
                }
            }
        }
    }

    @Test
    fun `terminal omissions survive distinct multi output pages and child cursors`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            suspend fun drain(capacity: Int, terminal: Boolean): Pair<List<io.github.amichne.kast.query.contract.QuerySymbol>, List<Pair<String, Long>>> {
                val reads = mutableListOf<Pair<String, Long>>()
                val service = recordingService(selected, reads, terminal = terminal, repeated = true)
                val first = request(twoHopPlan(selected), workLimit = 100L, resultLimit = capacity)
                var next = first
                val symbols = mutableListOf<io.github.amichne.kast.query.contract.QuerySymbol>()
                repeat(10) {
                    val page = service.run(next)
                    val result = when (page) {
                        is QueryExecutionResult.Complete -> page.result
                        is QueryExecutionResult.Qualified -> {
                            assertEquals(terminal, QueryLimitation.RELATION_INCOMPLETE in page.coverage.limitations)
                            page.result
                        }
                        is QueryExecutionResult.Rejected -> error(page.toString())
                    }
                    symbols += (result.items as io.github.amichne.kast.query.contract.QueryResultSet.Symbols).values
                    val continuation = (page as? QueryExecutionResult.Qualified)?.continuation
                    if (continuation is io.github.amichne.kast.query.contract.QueryContinuationState.Resumable) {
                        next = QueryExecutionRequest.create(first.plan, first.lease, first.budget, continuation.checkpoint).refined()
                    } else {
                        assertEquals(terminal, page is QueryExecutionResult.Qualified)
                        return symbols to reads
                    }
                }
                error("Query did not drain")
            }
            for (terminal in listOf(true, false)) {
                val (wide, _) = drain(20, terminal)
                val (paged, reads) = drain(1, terminal)
                assertEquals(listOf("C", "D"), paged.map { it.description.name.value }.sorted())
                assertEquals(wide.map { it.selector.fingerprint to it.connections.map(RelationFact::canonicalProjection) },
                    paged.map { it.selector.fingerprint to it.connections.map(RelationFact::canonicalProjection) })
                assertEquals(listOf("PaymentService" to 0L, "B" to 0L, "B" to 1L, "B" to 2L), reads)
            }
        }
    }

    private fun QueryServiceTest.twoHopPlan(selected: io.github.amichne.kast.symbol.contract.SymbolSelector) =
        exactReferencePlan(listOf(selected), listOf(
            QueryStepSyntax.Related(RelationMeaning.Callees),
            QueryStepSyntax.Related(RelationMeaning.Callees), QueryStepSyntax.Distinct))

    private fun QueryServiceTest.recordingService(
        selected: io.github.amichne.kast.symbol.contract.SymbolSelector,
        reads: MutableList<Pair<String, Long>>,
        leaf: Boolean = false,
        terminal: Boolean = true,
        repeated: Boolean = false,
    ) = QueryService(
        discoveryEmpty(false),
        exactOperations(describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
            resolve = { error("No discovery expected") }),
        SourceReadOperations { error("No source expected") },
        RelationOperations { read ->
            val position = read.providerCursor.nextPosition.value
            reads += read.subject.name.value to position
            val names = when (read.subject.name.value) {
                "PaymentService" -> listOf("B")
                "B" -> if (leaf) emptyList() else if (repeated) listOf("C", "C", "D") else listOf("C")
                else -> error("Unexpected subject")
            }
            val facts = names.mapIndexed { index, name ->
                val evidence = io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence.fromBoundary(
                    selected.file, 30 + name.single().code, 31 + name.single().code, name, "sample.$name",
                    io.github.amichne.kast.symbol.contract.CompilerSymbolKind.CLASSLIKE,
                    io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature.classLike("sample.$name").refined()).refined()
                val target = io.github.amichne.kast.relation.contract.RelationEndpoint.resolve(read.subject.lease, read.subject.scope, evidence).refined()
                RelationFact.create(read, read.subject, target,
                    RelationOccurrence.fromBoundary(selected.file, 200 + index, 201 + index).refined(),
                    RelationProvenance.K2_AUTHORED_SOURCE).refined()
            }.sorted()
            val page = facts.drop(position.toInt()).take(read.budget.resources.resultLimit.value)
            val batch = RelationBatch.create(read, page,
                RelationByteCount.parse(page.sumOf { it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() }).refined(),
                RelationWorkCount.parse(page.size.toLong()).refined(), RelationResultCount.parse(page.size).refined()).refined()
            val compilation = when {
                read.subject.name.value == "PaymentService" && terminal ->
                    RelationCompilation.qualifiedTerminal(batch, setOf(RelationLimitation.UNSUPPORTED_ITEM)).refined()
                position + page.size < facts.size -> RelationCompilation.qualifiedResumable(batch,
                    setOf(RelationLimitation.RESULT_LIMIT_REACHED), page.fold(read.providerCursor) { cursor, fact ->
                        cursor.advance(RelationProviderItemDescriptor.parse(fact.canonicalProjection()).refined())
                    }).refined()
                else -> RelationCompilation.complete(batch)
            }
            when (compilation) {
                is RelationCompilation.Complete -> RelationReadResult.Complete(batch, compilation.coverage)
                is RelationCompilation.Qualified -> RelationReadResult.Qualified(batch, compilation.coverage)
                is RelationCompilation.Rejected -> error(compilation.toString())
            }
        },
        clock = QueryNanoClock { 0L },
    )

    private fun relationPages(
        selected: io.github.amichne.kast.symbol.contract.SymbolSelector,
        positions: MutableList<Long>,
    ): RelationOperations = RelationOperations { read ->
        val position = read.providerCursor.nextPosition.value
        positions += position
        val fact =
            RelationFact.create(
                    read,
                    read.subject,
                    read.subject,
                    RelationOccurrence.fromBoundary(selected.file, 8, 9).refined(),
                    RelationProvenance.K2_AUTHORED_SOURCE,
                )
                .refined()
        val batch =
            RelationBatch.create(
                    read,
                    listOf(fact),
                    RelationByteCount.parse(fact.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong())
                        .refined(),
                    RelationWorkCount.parse(1).refined(),
                    RelationResultCount.parse(1).refined(),
                )
                .refined()
        if (position == 0L) {
            val coverage =
                RelationIncompleteCoverage.resumable(
                        batch,
                        setOf(RelationLimitation.RESULT_LIMIT_REACHED),
                        read.providerCursor.advance(
                            RelationProviderItemDescriptor.parse("sample.catalog.first").refined()
                        ),
                    )
                    .refined()
            RelationReadResult.Qualified(batch, coverage)
        } else {
            val complete = RelationCompilation.complete(batch)
            RelationReadResult.Complete(batch, complete.coverage)
        }
    }

    private fun <Value, Failure> io.github.amichne.kast.kernel.Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> value
            is io.github.amichne.kast.kernel.Refinement.Rejected -> error("Expected refinement")
        }
}
