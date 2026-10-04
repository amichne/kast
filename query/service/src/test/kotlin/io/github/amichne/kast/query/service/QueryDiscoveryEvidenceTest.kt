package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryCompositionInput
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryDeclarationKinds
import io.github.amichne.kast.query.contract.QueryDiscoveryObservation
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryJoinMode
import io.github.amichne.kast.query.contract.QueryMatch
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryScope
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ResolvedSymbol
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryDiscoveryEvidenceTest {
    @Test
    fun `every blocked discovery cause prevents complete empty evidence at construction`() = runTest {
        val fixture = DiscoveryEvidenceFixture()
        for (cause in SymbolDiscoveryBlockCause.entries) {
            val discovery = SymbolDiscoveryOperations { request ->
                val batch =
                    SymbolDiscoveryBatch.create(
                            request,
                            emptyList(),
                            SymbolDiscoveryByteCount.parse(0L).refinedEvidence(),
                            SymbolDiscoveryWorkCount.Zero,
                            SymbolDiscoveryTimings(
                                SymbolDiscoveryElapsedNanoseconds.Zero,
                                SymbolDiscoveryElapsedNanoseconds.Zero,
                            ),
                        )
                        .refinedEvidence()
                io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult.Discovered(
                    SymbolDiscoveryOutcome.Qualified(
                        batch,
                        SymbolDiscoveryQualifications.from(setOf(SymbolDiscoveryQualification.PROVIDER_FAILURE))
                            .refinedEvidence(),
                        SymbolDiscoveryProgress.Blocked(cause),
                    )
                )
            }
            val request = fixture.scope.request(fixture.discoveryPlan(), 100L)
            val page = fixture.scope.service(discovery = discovery).run(request) as QueryExecutionResult.Qualified
            assertEquals(
                QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION),
                QueryExecutionResult.Complete.create(
                    page.result,
                    QueryCoverage.Complete(QueryCount.parse(0).refinedEvidence()),
                ),
                "blocked cause $cause remains unresolved",
            )
        }
    }

    @Test
    fun `retained discovery witnesses survive every composition without double counting`() = runTest {
        val fixture = DiscoveryEvidenceFixture()
        val read = fixture.read()
        val observation = read.execution.result.discoveryObservations.single()
        val retained = fixture.retainEmpty(read, listOf(observation))
        val operators =
            listOf(
                emptyList(),
                listOf(QueryStepSyntax.Concat(QueryCompositionInput.Retained(retained))),
                listOf(QueryStepSyntax.Union(retained)),
                listOf(QueryStepSyntax.Intersect(retained)),
                listOf(QueryStepSyntax.Difference(retained)),
                listOf(QueryStepSyntax.Join(QueryJoinMode.Semi, retained)),
            )
        for (steps in operators) {
            val composed = fixture.retainedPlan(retained, steps)
            val result =
                fixture.scope.service().run(fixture.scope.request(composed, 100L)) as QueryExecutionResult.Complete
            assertEquals(listOf(observation), result.result.discoveryObservations, "operator $steps preserves evidence")
        }
    }

    @Test
    fun `metadata byte exhaustion preserves useful evidence and advances retained work`() = runTest {
        val fixture = DiscoveryEvidenceFixture()
        var nativeCalls = 0
        val producer = progressiveQueryDiscoveryCandidates(listOf("Declaration"), mutableListOf())
        val read =
            fixture.read(
                discovery =
                    SymbolDiscoveryOperations {
                        nativeCalls++
                        producer.discover(it)
                    }
            )
        val first = read.execution.result.discoveryObservations.single()
        val second =
            fixture
                .read(setOf(CompilerSymbolKind.CLASSLIKE, CompilerSymbolKind.FUNCTION))
                .execution
                .result
                .discoveryObservations
                .single()
        val plan = fixture.retainedPlan(fixture.retainEmpty(read, listOf(first, second)))
        val service =
            fixture.scope.service(
                discovery =
                    SymbolDiscoveryOperations {
                        error("Retained evidence never repeats discovery")
                    }
            )
        assertTrue(second.projectedUtf8Size() > first.projectedUtf8Size())
        fixture.assertOversizedWitness(service, plan, first)
        fixture.assertDrain(service, plan, first, second)
        assertEquals(1, nativeCalls)
    }
}

private data class DiscoveryEvidenceRead(
    val request: QueryExecutionRequest,
    val execution: QueryExecutionResult.Complete,
)

private class DiscoveryEvidenceFixture {
    val scope = QueryServiceTest()

    private fun plan(kinds: Set<CompilerSymbolKind>) =
        scope.admittedPlan(
            QuerySourceSyntax.Symbols(
                QueryDiscoverySyntax(
                    QueryMatch.All,
                    QueryScope.Unrestricted,
                    QueryDeclarationKinds.from(kinds).refinedEvidence(),
                )
            ),
            emptyList(),
            QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refinedEvidence()),
        )

    fun discoveryPlan(): AdmittedQueryPlan = plan(setOf(CompilerSymbolKind.CLASSLIKE))

    suspend fun read(
        kinds: Set<CompilerSymbolKind> = setOf(CompilerSymbolKind.CLASSLIKE),
        discovery: SymbolDiscoveryOperations =
            progressiveQueryDiscoveryCandidates(listOf("Declaration"), mutableListOf()),
    ): DiscoveryEvidenceRead {
        val request = scope.request(plan(kinds), 100L)
        val service =
            scope.service(
                discovery = discovery,
                exact =
                    scope.exactOperations {
                        SymbolResolutionResult.Resolved(ResolvedSymbol(scope.selector(it)))
                    },
            )
        return DiscoveryEvidenceRead(request, service.run(request) as QueryExecutionResult.Complete)
    }

    fun retainEmpty(
        read: DiscoveryEvidenceRead,
        observations: List<QueryDiscoveryObservation>,
    ): QueryRetainedResult.Symbols =
        QueryRetainedResult.capture(
                read.request.lease,
                QueryExecutionResult.Complete.create(
                    read.execution.result.copy(
                        rows = QueryRows.Symbols.of(emptyList()),
                        discoveryObservations = observations,
                    ),
                    QueryCoverage.Complete(QueryCount.parse(0).refinedEvidence()),
                ),
            )
            .refinedEvidence() as QueryRetainedResult.Symbols

    fun retainedPlan(retained: QueryRetainedResult, steps: List<QueryStepSyntax> = emptyList()): AdmittedQueryPlan =
        scope.admittedPlan(
            QuerySourceSyntax.Retained(retained),
            steps,
            QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refinedEvidence()),
        )

    suspend fun assertOversizedWitness(
        service: QueryService,
        plan: AdmittedQueryPlan,
        first: QueryDiscoveryObservation,
    ) {
        val page =
            service.run(scope.request(plan, 100L, returnedBytes = first.projectedUtf8Size()))
                as QueryExecutionResult.Qualified
        assertEquals(listOf(first), page.result.discoveryObservations)
        assertEquals(QueryContinuationState.Terminal(QueryTerminalReason.OUTPUT_ITEM_TOO_LARGE), page.continuation)
    }

    suspend fun assertDrain(
        service: QueryService,
        plan: AdmittedQueryPlan,
        first: QueryDiscoveryObservation,
        second: QueryDiscoveryObservation,
    ) {
        val grant = maxOf(first.projectedUtf8Size(), second.projectedUtf8Size())
        assertTrue(grant < first.projectedUtf8Size() + second.projectedUtf8Size())
        val request = scope.request(plan, 100L, returnedBytes = grant)
        val page = service.run(request) as QueryExecutionResult.Qualified
        assertEquals(listOf(first), page.result.discoveryObservations)
        val continuation = page.continuation as QueryContinuationState.Resumable
        val successor =
            service.run(
                QueryExecutionRequest.create(plan, request.lease, request.budget, continuation.checkpoint)
                    .refinedEvidence()
            ) as QueryExecutionResult.Complete
        assertEquals(listOf(second), successor.result.discoveryObservations)
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refinedEvidence(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
