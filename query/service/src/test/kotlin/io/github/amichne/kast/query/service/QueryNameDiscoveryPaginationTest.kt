package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryDeclarationKinds
import io.github.amichne.kast.query.contract.QueryDiscoveryProgress
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryMatch
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryScope
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ResolvedSymbol
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryNameDiscoveryPaginationTest {
    @Test
    fun `one work unit cannot start exact-name discovery without refinement authority`() = runTest {
        val fixture = QueryServiceTest()
        val service =
            fixture.service(
                discovery =
                    SymbolDiscoveryOperations {
                        error("Exact-name discovery requires a separate refinement work unit")
                    },
                clock = QueryNanoClock { 0L },
            )
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                service.run(fixture.request(plan(fixture), workLimit = 1)),
            )

        assertTrue(result.result.symbolRows().isEmpty())
        assertTrue(result.result.discoveryObservations.isEmpty())
        assertTrue(result.result.failures.isEmpty())
        assertEquals(listOf(QueryLimitation.WORK_LIMIT_REACHED), result.coverage.limitations)
        assertEquals(QueryContinuationState.Terminal(QueryTerminalReason.NO_PROGRESS), result.continuation)
    }

    @Test
    fun `five same-name candidates drain as two two one without repeating discovery or refinement`() = runTest {
        val fixture = QueryServiceTest()
        val requests = mutableListOf<SymbolDiscoveryRequest>()
        val resolved = mutableListOf<SymbolSelector>()
        val service = service(fixture, requests, resolved)
        val request = fixture.request(plan(fixture), workLimit = 10, resultLimit = 2, elapsedMillis = 1_000)

        val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(request))
        val firstContinuation = assertInstanceOf(QueryContinuationState.Resumable::class.java, first.continuation)
        assertEquals(2, first.result.symbolRows().size)
        assertEquals(listOf(QueryLimitation.RESULT_LIMIT_REACHED), first.coverage.limitations)
        assertEquals(QueryDiscoveryProgress.Exhausted, first.result.discoveryObservations.single().progress)
        assertEquals(request.lease, firstContinuation.checkpoint.lease)
        assertEquals(request.plan, firstContinuation.checkpoint.plan)
        assertTrue(firstContinuation.checkpoint.retainedBytes > 0)

        val second =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                service.run(resume(request, firstContinuation)),
            )
        val secondContinuation = assertInstanceOf(QueryContinuationState.Resumable::class.java, second.continuation)
        assertEquals(2, second.result.symbolRows().size)
        assertEquals(listOf(QueryLimitation.RESULT_LIMIT_REACHED), second.coverage.limitations)
        assertTrue(secondContinuation.checkpoint.retainedBytes < firstContinuation.checkpoint.retainedBytes)

        val last =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                service.run(resume(request, secondContinuation)),
            )
        assertEquals(1, last.result.symbolRows().size)
        assertEquals(5, last.coverage.resultCount.value)

        val selectors =
            (first.result.symbolRows() + second.result.symbolRows() + last.result.symbolRows()).map { it.selector }
        assertEquals(listOf(0, 30, 60, 90, 120), selectors.map { it.range.startInclusive })
        assertEquals(resolved, selectors)
        assertEquals(5, selectors.map { it.fingerprint }.distinct().size)
        assertTrue(selectors.all { it.lease == request.lease && it.name.value == "Application" })
        val discovery = requests.single()
        assertEquals(5, discovery.budget.resources.resultLimit.value)
        assertEquals(5L, discovery.budget.resources.workUnitLimit.value)
        assertEquals(1_000L, discovery.budget.resources.elapsedTimeLimit.value)
        assertEquals(request.budget.returnedBytes.value, discovery.budget.returnedBytes.value)
    }

    @Test
    fun `work-bounded exact discovery retains its permanent qualification after pending candidates drain`() = runTest {
        val fixture = QueryServiceTest()
        val requests = mutableListOf<SymbolDiscoveryRequest>()
        val resolved = mutableListOf<SymbolSelector>()
        val service = service(fixture, requests, resolved)
        val request = fixture.request(plan(fixture), workLimit = 6, resultLimit = 2)

        val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(request))
        val continuation = assertInstanceOf(QueryContinuationState.Resumable::class.java, first.continuation)
        assertEquals(2, first.result.symbolRows().size)
        assertTrue(QueryLimitation.DISCOVERY_INCOMPLETE in first.coverage.limitations)
        assertTrue(QueryLimitation.WORK_LIMIT_REACHED in first.coverage.limitations)
        assertInstanceOf(
            QueryDiscoveryProgress.Blocked::class.java,
            first.result.discoveryObservations.single().progress,
        )

        val last =
            assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(resume(request, continuation)))
        assertEquals(1, last.result.symbolRows().size)
        assertEquals(
            QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE),
            last.continuation,
        )
        assertEquals(
            setOf(QueryLimitation.WORK_LIMIT_REACHED, QueryLimitation.DISCOVERY_INCOMPLETE),
            last.coverage.limitations.toSet(),
        )
        assertEquals(listOf(0, 30, 60), resolved.map { it.range.startInclusive })
        assertEquals(3, requests.single().budget.resources.resultLimit.value)
        assertEquals(3L, requests.single().budget.resources.workUnitLimit.value)
    }

    @Test
    fun `fuzzy discovery keeps its ranked presentation capacity`() = runTest {
        val fixture = QueryServiceTest()
        val requests = mutableListOf<SymbolDiscoveryRequest>()
        val service =
            fixture.service(
                discovery =
                    SymbolDiscoveryOperations {
                        requests += it
                        fixture.discoveryEmpty(qualified = false).discover(it)
                    },
                clock = QueryNanoClock { 0L },
            )

        assertInstanceOf(
            QueryExecutionResult.Complete::class.java,
            service.run(fixture.request(plan(fixture, SymbolDiscoveryMatch.FUZZY), workLimit = 10, resultLimit = 2)),
        )
        assertEquals(2, requests.single().budget.resources.resultLimit.value)
        assertEquals(10L, requests.single().budget.resources.workUnitLimit.value)
    }

    private fun plan(fixture: QueryServiceTest, match: SymbolDiscoveryMatch = SymbolDiscoveryMatch.EXACT_NAME) =
        fixture.admittedPlan(
            QuerySourceSyntax.Symbols(
                QueryDiscoverySyntax(
                    QueryMatch.Name(SymbolDiscoveryPattern.parse("Application").refined(), match),
                    QueryScope.Unrestricted,
                    QueryDeclarationKinds.from(setOf(CompilerSymbolKind.CLASSLIKE)).refined(),
                )
            ),
            output = QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
        )

    private fun service(
        fixture: QueryServiceTest,
        requests: MutableList<SymbolDiscoveryRequest>,
        resolved: MutableList<SymbolSelector>,
    ) =
        fixture.service(
            discovery =
                SymbolDiscoveryOperations { request ->
                    assertTrue(requests.isEmpty(), "A saved candidate must not repeat native discovery")
                    requests += request
                    val size =
                        minOf(
                            5,
                            request.budget.resources.resultLimit.value,
                            request.budget.resources.workUnitLimit.value.toInt(),
                        )
                    val batch = candidateBatch(request, size)
                    SymbolDiscoveryResult.Discovered(
                        if (size == 5) SymbolDiscoveryOutcome.Complete(batch)
                        else
                            SymbolDiscoveryOutcome.Qualified(
                                batch,
                                SymbolDiscoveryQualifications.from(
                                        setOf(
                                            if (size.toLong() == request.budget.resources.workUnitLimit.value)
                                                SymbolDiscoveryQualification.WORK_LIMIT_REACHED
                                            else SymbolDiscoveryQualification.RESULT_LIMIT_REACHED
                                        )
                                    )
                                    .refined(),
                            )
                    )
                },
            exact =
                fixture.exactOperations { selection ->
                    val selector = selector(selection)
                    assertTrue(selector !in resolved, "Exact refinement must run only once per retained candidate")
                    resolved += selector
                    SymbolResolutionResult.Resolved(ResolvedSymbol(selector))
                },
            clock = QueryNanoClock { 0L },
        )

    private fun candidateBatch(request: SymbolDiscoveryRequest, size: Int): SymbolDiscoveryBatch {
        val candidates =
            List(size) { index ->
                SymbolDiscoveryCandidate.fromBoundary(
                        SymbolDiscoveryKind.CLASS,
                        "Application",
                        request.scope.lease,
                        Path.of("/workspace/Application.kt"),
                        "file:///workspace/Application.kt",
                        index * 30,
                    )
                    .refined()
            }
        return SymbolDiscoveryBatch.create(
                request,
                candidates,
                SymbolDiscoveryByteCount.parse(candidates.sumOf { it.projectedUtf8Size().value }).refined(),
                SymbolDiscoveryWorkCount.parse(size.toLong()).refined(),
                SymbolDiscoveryTimings(
                    SymbolDiscoveryElapsedNanoseconds.Zero,
                    SymbolDiscoveryElapsedNanoseconds.Zero,
                ),
            )
            .refined()
    }

    private fun selector(selection: SymbolDiscoverySelection): SymbolSelector {
        val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
        val name = "sample.Owner${location.offset.value}.Application"
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    location.file,
                    location.offset.value,
                    location.offset.value + 20,
                    "Application",
                    name,
                    CompilerSymbolKind.CLASSLIKE,
                    CanonicalCompilerSignature.classLike(name).refined(),
                )
                .refined()
        return SymbolSelector.issue(selection, evidence).refined()
    }

    private fun resume(request: QueryExecutionRequest, continuation: QueryContinuationState.Resumable) =
        QueryExecutionRequest.create(request.plan, request.lease, request.budget, continuation.checkpoint).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid fixture: $failure")
        }
}
