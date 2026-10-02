package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryArrivalEvidence
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryPredicate
import io.github.amichne.kast.query.contract.QueryPrimitiveField
import io.github.amichne.kast.query.contract.QueryPrimitiveOperator
import io.github.amichne.kast.query.contract.QueryPrimitiveValue
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QueryTextMatches
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ResolvedSymbol
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWord
import io.github.amichne.kast.symbol.contract.SymbolExactRejection
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.symbol.contract.SymbolTextMatch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryTextDiscoveryTest {
    private val text = QueryTextDiscoveryFixture()

    @Test
    fun `exhausted word discovery without matches is complete and does not refine`() = runTest {
        val fixture = QueryServiceTest()
        val service = fixture.service(discovery = fixture.discoveryEmpty(qualified = false))
        val result =
            assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(fixture.request(text.plan(), 8)))
        assertTrue(result.result.symbolRows().isEmpty())
        assertTrue(result.result.failures.isEmpty())
        assertEquals(
            io.github.amichne.kast.query.contract.QueryDiscoveryProgress.Exhausted,
            result.result.discoveryObservations.single().progress,
        )
    }

    @Test
    fun `word discovery retains scope and exact owner with lexical evidence`() = runTest {
        val fixture = QueryServiceTest()
        val match = text.match(fixture)
        val requests = mutableListOf<SymbolDiscoveryRequest>()
        var refinements = 0
        val service =
            fixture.service(
                discovery = text.discovery(match) { requests += it },
                exact =
                    fixture.exactOperations { selection ->
                        refinements++
                        SymbolResolutionResult.Resolved(ResolvedSymbol(fixture.selector(selection)))
                    },
            )
        val result =
            assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(fixture.request(text.plan(), 8)))
        val symbol = result.result.symbolRows().single()
        val request = requests.single()

        assertEquals(SymbolDiscoveryTarget.TextDeclarations(match.word), request.target)
        assertEquals("services", request.constraints.directory?.directory?.value)
        assertEquals(setOf(CompilerSymbolKind.CLASSLIKE), request.constraints.declarationKinds?.values)
        assertEquals(text.sourceSets(), request.constraints.sourceSets)
        assertEquals(4, request.budget.resources.resultLimit.value)
        assertEquals(4L, request.budget.resources.workUnitLimit.value)
        assertEquals(1, refinements)
        assertEquals(listOf(match), symbol.textMatches.values)
        assertEquals(QueryArrivalEvidence.None, symbol.arrival)
        assertTrue(symbol.connections.isEmpty())
        assertEquals(match.declarationRange.startInclusive.value, symbol.selector.range.startInclusive)
        assertEquals(match.declarationRange.endExclusive.value, symbol.selector.range.endExclusive)
        assertEquals(request.target, result.result.discoveryObservations.single().target)
    }

    @Test
    fun `failed owner refinement preserves finite failure without a lexical success row`() = runTest {
        val fixture = QueryServiceTest()
        val service =
            fixture.service(
                discovery = text.discovery(text.match(fixture)),
                exact =
                    fixture.exactOperations {
                        SymbolResolutionResult.Rejected(SymbolExactRejection.AMBIGUOUS_DECLARATION)
                    },
            )
        val result =
            assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(fixture.request(text.plan(), 8)))

        assertTrue(result.result.symbolRows().isEmpty())
        assertEquals(
            SymbolExactRejection.AMBIGUOUS_DECLARATION,
            (result.result.failures.single() as QueryItemFailure.Refinement).reason,
        )
        assertTrue(QueryLimitation.REFINEMENT_INCOMPLETE in result.coverage.limitations)
    }

    @Test
    fun `stopped word provider remains terminally incomplete with usable exact positives`() = runTest {
        val fixture = QueryServiceTest()
        val service =
            fixture.service(
                discovery = text.discovery(text.match(fixture), stopped = true),
                exact =
                    fixture.exactOperations { SymbolResolutionResult.Resolved(ResolvedSymbol(fixture.selector(it))) },
            )
        val result =
            assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(fixture.request(text.plan(), 8)))

        assertEquals(1, result.result.symbolRows().size)
        assertTrue(QueryLimitation.DISCOVERY_INCOMPLETE in result.coverage.limitations)
        assertInstanceOf(
            io.github.amichne.kast.query.contract.QueryContinuationState.Terminal::class.java,
            result.continuation,
        )
        assertInstanceOf(
            io.github.amichne.kast.query.contract.QueryDiscoveryProgress.Blocked::class.java,
            result.result.discoveryObservations.single().progress,
        )
    }

    @Test
    fun `exact owner range mismatch rejects instead of attaching lexical evidence`() = runTest {
        val fixture = QueryServiceTest()
        val match = text.match(fixture)
        val foreignRange =
            SymbolTextMatch.fromBoundary(match.word, match.lease, match.file, 12, 19, 7, 28, "launchd", 12, 1).refined()
        val service =
            fixture.service(
                discovery = text.discovery(foreignRange),
                exact =
                    fixture.exactOperations { SymbolResolutionResult.Resolved(ResolvedSymbol(fixture.selector(it))) },
            )
        val result =
            assertInstanceOf(QueryExecutionResult.Rejected::class.java, service.run(fixture.request(text.plan(), 8)))
        assertEquals(
            io.github.amichne.kast.query.contract.QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION,
            result.reason,
        )
    }

    @Test
    fun `presentation result capacity does not discard later owner after a predicate excludes the first`() = runTest {
        val fixture = QueryServiceTest()
        var refinements = 0
        val service =
            fixture.service(
                discovery = text.discoveryPair(fixture),
                exact =
                    fixture.exactOperations {
                        refinements++
                        SymbolResolutionResult.Resolved(ResolvedSymbol(fixture.selector(it)))
                    },
            )
        val filter =
            QueryStepSyntax.Where(
                QueryPredicate.Primitive(
                    QueryPrimitiveField.NAME,
                    QueryPrimitiveOperator.EQUALS,
                    QueryPrimitiveValue.parse("PaymentService").refined(),
                )
            )
        val result =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                service.run(fixture.request(text.plan(listOf(filter)), 8, resultLimit = 1)),
            )

        assertEquals(2, refinements)
        assertEquals("PaymentService", result.result.symbolRows().single().description.name.value)
    }

    @Test
    fun `failure presentation defers later owner in a real checkpoint without repeating discovery`() = runTest {
        val fixture = QueryServiceTest()
        var discoveries = 0
        var refinements = 0
        val service =
            fixture.service(
                discovery = text.discoveryPair(fixture) { discoveries++ },
                exact =
                    fixture.exactOperations {
                        refinements++
                        if (it.candidate.name.value == "Excluded")
                            SymbolResolutionResult.Rejected(SymbolExactRejection.AMBIGUOUS_DECLARATION)
                        else SymbolResolutionResult.Resolved(ResolvedSymbol(fixture.selector(it)))
                    },
            )
        val request = fixture.request(text.plan(), 8, resultLimit = 1)
        val prefix = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(request))
        assertEquals(1, prefix.result.failures.size)
        assertTrue(prefix.result.symbolRows().isEmpty())
        val continuation = assertInstanceOf(QueryContinuationState.Resumable::class.java, prefix.continuation)
        val resume =
            QueryExecutionRequest.create(request.plan, request.lease, request.budget, continuation.checkpoint).refined()
        val suffix = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(resume))

        assertEquals("PaymentService", suffix.result.symbolRows().single().description.name.value)
        assertEquals(
            30,
            suffix.result.symbolRows().single().textMatches.values.single().declarationRange.startInclusive.value,
        )
        assertEquals(1, discoveries)
        assertEquals(2, refinements)
    }

    @Test
    fun `same owner composition merges distinct lexical exemplars without repetition`() = runTest {
        val fixture = QueryServiceTest()
        val first = text.match(fixture)
        val second =
            SymbolTextMatch.fromBoundary(
                    SymbolDiscoveryWord.parse("bootstrap").refined(),
                    first.lease,
                    first.file,
                    12,
                    21,
                    7,
                    27,
                    "bootstrap",
                    12,
                    1,
                )
                .refined()
        val service =
            fixture.service(
                discovery = text.discovery(first),
                exact =
                    fixture.exactOperations { SymbolResolutionResult.Resolved(ResolvedSymbol(fixture.selector(it))) },
            )
        val result =
            assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(fixture.request(text.plan(), 8)))
        val row = result.result.symbolRows().single()
        val other = row.copy(textMatches = QueryTextMatches.singleton(second))
        val merged = mergeRows(row, other).refined()
        assertEquals(listOf(first, second), mergeRows(merged, row).refined().textMatches.values)
    }

    @Test
    fun `one remaining work unit cannot begin discovery without refinement authority`() = runTest {
        val fixture = QueryServiceTest()
        val service =
            fixture.service(
                discovery =
                    SymbolDiscoveryOperations { error("No indexed search is authorized without a refinement reserve") }
            )
        val result =
            assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(fixture.request(text.plan(), 1)))
        assertTrue(result.result.symbolRows().isEmpty())
        assertTrue(QueryLimitation.WORK_LIMIT_REACHED in result.coverage.limitations)
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = (this as Refinement.Refined).value
}
