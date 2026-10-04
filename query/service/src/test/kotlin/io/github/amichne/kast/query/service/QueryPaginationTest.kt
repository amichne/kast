package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryDeclarationKinds
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryMatch
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryScope
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
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
    fun `discovery candidates do not spend returned bytes while producer evidence does`() = runTest {
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
            val observed =
                assertInstanceOf(
                        QueryExecutionResult.Complete::class.java,
                        service.run(request(symbolPlan(), workLimit = 8L)),
                    )
                    .result
                    .discoveryObservations
                    .single()
            val grant = bytes + observed.projectedUtf8Size()
            val result = service.run(request(symbolPlan(), workLimit = 8L, returnedBytes = grant))
            assertEquals(1, result.symbolCount())
            assertEquals(listOf(observed), (result as QueryExecutionResult.Complete).result.discoveryObservations)
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
            var discoveries = 0
            var resolutions = 0
            val service =
                service(
                    discovery =
                        SymbolDiscoveryOperations { input ->
                            assertEquals(1L, input.budget.resources.workUnitLimit.value)
                            assertEquals(0, discoveries++, "Retained candidates must not repeat discovery")
                            workLimitedDiscovery(discovery, input)
                        },
                    exact =
                        exactOperations {
                            resolutions++
                            SymbolResolutionResult.Resolved(
                                io.github.amichne.kast.symbol.contract.ResolvedSymbol(selector(it))
                            )
                        },
                )
            val first = request(allDeclarationsPlan(this), workLimit = 1L)
            val page = service.run(first) as QueryExecutionResult.Qualified
            assertEquals(0, page.symbolCount())
            assertEquals(1, discoveries)
            assertEquals(0, resolutions)
            val checkpoint =
                (page.continuation as io.github.amichne.kast.query.contract.QueryContinuationState.Resumable).checkpoint
            val resumed = request(first.plan, workLimit = 8L)
            val last =
                service.run(QueryExecutionRequest.create(first.plan, first.lease, resumed.budget, checkpoint).refined())
                    as QueryExecutionResult.Qualified
            assertEquals(1, last.symbolCount())
            assertEquals(1, discoveries)
            assertEquals(1, resolutions)
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
                val minimum =
                    when (page) {
                        is QueryExecutionResult.Complete -> page.coverage.resultCount.value
                        is QueryExecutionResult.Qualified -> page.coverage.knownMinimum.value
                        is QueryExecutionResult.ImpactRejected -> error("Unexpected impact rejection")
                        is QueryExecutionResult.Rejected -> error("Unexpected rejection")
                    }
                assertEquals(count, minimum)
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
            assertEquals(selected, complete.result.symbolRows().single().selector)
            assertEquals(3, descriptions)
        }
    }

    private fun <Value, Failure> io.github.amichne.kast.kernel.Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> value
            is io.github.amichne.kast.kernel.Refinement.Rejected -> error("Expected refinement")
        }

    private fun allDeclarationsPlan(fixture: QueryServiceTest) =
        fixture.admittedPlan(
            source =
                QuerySourceSyntax.Symbols(
                    QueryDiscoverySyntax(
                        QueryMatch.All,
                        QueryScope.Unrestricted,
                        QueryDeclarationKinds.from(setOf(CompilerSymbolKind.CLASSLIKE)).refined(),
                    )
                ),
            output = QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
        )

    private suspend fun workLimitedDiscovery(
        discovery: SymbolDiscoveryOperations,
        request: SymbolDiscoveryRequest,
    ): SymbolDiscoveryResult {
        val complete =
            (discovery.discover(request) as SymbolDiscoveryResult.Discovered).outcome as SymbolDiscoveryOutcome.Complete
        return SymbolDiscoveryResult.Discovered(
            SymbolDiscoveryOutcome.Qualified(
                complete.batch,
                SymbolDiscoveryQualifications.from(setOf(SymbolDiscoveryQualification.WORK_LIMIT_REACHED)).refined(),
            )
        )
    }
}
