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
    fun `relation cursor and distinct evidence survive a work page without prefix replay`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val positions = mutableListOf<Long>()
            var descriptions = 0
            val service =
                QueryService(
                    discoveryEmpty(false),
                    exactOperations(
                        describe = {
                            descriptions++
                            SymbolDescriptionResult.Described(SymbolDescription.from(it))
                        },
                        resolve = { error("No discovery expected") },
                    ),
                    SourceReadOperations { error("No source expected") },
                    relationPages(selected, positions),
                    unexpectedQueryTraversal(),
                    queryTestTraversalCeiling(),
                )
            val first =
                request(
                    exactReferencePlan(
                        listOf(selected),
                        listOf(QueryStepSyntax.Related(RelationMeaning.Callees), QueryStepSyntax.Distinct),
                    ),
                    workLimit = 2L,
                    resultLimit = 1,
                )
            val page = service.run(first) as QueryExecutionResult.Qualified
            assertEquals(0, page.symbolCount())
            assertEquals(listOf(0L), positions)
            assertEquals(1, descriptions)
            val checkpoint =
                (page.continuation as io.github.amichne.kast.query.contract.QueryContinuationState.Resumable).checkpoint
            val resumed = request(first.plan, workLimit = 8L, resultLimit = 1)
            val last =
                service.run(QueryExecutionRequest.create(first.plan, first.lease, resumed.budget, checkpoint).refined())
            val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, last)
            assertEquals(1, complete.symbolCount())
            assertEquals(2, complete.result.items.single().connections.size)
            assertEquals(listOf(0L, 1L), positions)
            assertEquals(1, descriptions)
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
    fun `distinct accumulation survives a work page without prefix replay`() = runTest {
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
            val first =
                request(
                    exactReferencePlan(List(3) { selected }, listOf(QueryStepSyntax.Distinct)),
                    workLimit = 1L,
                    resultLimit = 1,
                )
            val page = service.run(first) as QueryExecutionResult.Qualified
            assertEquals(0, page.symbolCount())
            assertEquals(1, descriptions)
            val continuation =
                page.continuation as io.github.amichne.kast.query.contract.QueryContinuationState.Resumable
            val resumed = request(first.plan, workLimit = 8L, resultLimit = 1)
            val last =
                service.run(
                    QueryExecutionRequest.create(first.plan, first.lease, resumed.budget, continuation.checkpoint)
                        .refined()
                )
            val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, last)
            assertEquals(1, complete.symbolCount())
            assertEquals(selected, complete.result.items.single().selector)
            assertEquals(3, descriptions)
        }
    }

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
                    RelationOccurrence.fromBoundary(selected.file, 8 + position.toInt(), 9 + position.toInt())
                        .refined(),
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
