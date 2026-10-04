package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryDeclarationKinds
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryMatch
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryPlanAdmission
import io.github.amichne.kast.query.contract.QueryPlanCompiler
import io.github.amichne.kast.query.contract.QueryPlanSyntax
import io.github.amichne.kast.query.contract.QueryScope
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryActiveInput
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryInputRevision
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRemainder
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.symbol.contract.candidateOrder
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryIncrementalDiscoveryTest {
    @Test
    fun `all declarations result bound preserves undiscovered input`() = runTest {
        val fixture = QueryServiceTest()
        val syntax =
            QueryDiscoverySyntax(
                QueryMatch.All,
                QueryScope.Unrestricted,
                QueryDeclarationKinds.from(setOf(CompilerSymbolKind.CLASSLIKE)).refined(),
            )
        val plan =
            (QueryPlanCompiler.admit(
                    QueryPlanSyntax(
                        QuerySourceSyntax.Symbols(syntax),
                        emptyList(),
                        QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
                    )
                ) as QueryPlanAdmission.Admitted)
                .plan
        val service =
            fixture.service(
                discovery = unfinishedDiscovery(),
                exact =
                    fixture.exactOperations { selection ->
                        SymbolResolutionResult.Resolved(
                            io.github.amichne.kast.symbol.contract.ResolvedSymbol(fixture.selector(selection))
                        )
                    },
            )
        val first = service.run(fixture.request(plan, workLimit = 8L, resultLimit = 1))
        val qualified = first as QueryExecutionResult.Qualified
        assertEquals(1, qualified.result.symbolRows().size)
        assertTrue(
            qualified.continuation is io.github.amichne.kast.query.contract.QueryContinuationState.Resumable,
            "A bounded ALL producer must preserve undiscovered declarations in its checkpoint",
        )
    }

    @Test
    fun `rejected early candidates keep driving discovery until downstream accepts a useful result`() = runTest {
        val fixture = QueryServiceTest()
        val syntax =
            QueryDiscoverySyntax(
                QueryMatch.All,
                QueryScope.Unrestricted,
                QueryDeclarationKinds.from(setOf(CompilerSymbolKind.CLASSLIKE)).refined(),
            )
        val plan =
            fixture.admittedPlan(
                QuerySourceSyntax.Symbols(syntax),
                listOf(
                    QueryStepSyntax.Where(
                        io.github.amichne.kast.query.contract.QueryPredicate.Primitive(
                            io.github.amichne.kast.query.contract.QueryPrimitiveField.NAME,
                            io.github.amichne.kast.query.contract.QueryPrimitiveOperator.STARTS_WITH,
                            io.github.amichne.kast.query.contract.QueryPrimitiveValue.parse("Accepted").refined(),
                        )
                    )
                ),
                QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
            )
        val examined = mutableListOf<Int>()
        val names = List(12) { "Rejected$it" } + "Accepted"
        val service =
            fixture.service(
                discovery = progressiveQueryDiscoveryCandidates(names, examined),
                exact =
                    fixture.exactOperations { selection ->
                        SymbolResolutionResult.Resolved(
                            io.github.amichne.kast.symbol.contract.ResolvedSymbol(fixture.selector(selection))
                        )
                    },
            )
        val result = service.run(fixture.request(plan, 100, resultLimit = 1))
        assertEquals(
            listOf("Accepted"),
            when (result) {
                is QueryExecutionResult.Complete -> result.result.symbolRows()
                is QueryExecutionResult.Qualified -> result.result.symbolRows()
                is QueryExecutionResult.ImpactRejected -> error("Unexpected impact rejection")
                is QueryExecutionResult.Rejected -> error("Unexpected rejection ${result.reason}")
            }.map { it.description.name.value },
        )
        assertEquals(names.indices.toList(), examined)
    }

    @Test
    fun `query successors at limits one five and twenty exhaust producer input without repeated refinements`() =
        runTest {
            val fixture = QueryServiceTest()
            val syntax =
                QueryDiscoverySyntax(
                    QueryMatch.All,
                    QueryScope.Unrestricted,
                    QueryDeclarationKinds.from(setOf(CompilerSymbolKind.CLASSLIKE)).refined(),
                )
            val plan =
                fixture.admittedPlan(
                    QuerySourceSyntax.Symbols(syntax),
                    emptyList(),
                    QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
                )
            val names = List(23) { "Declaration$it" }
            for (limit in listOf(1, 5, 20)) {
                val discovered = mutableListOf<Int>()
                val refined = mutableListOf<Int>()
                val service =
                    fixture.service(
                        discovery = progressiveQueryDiscoveryCandidates(names, discovered),
                        exact = recordingRefinement(fixture, refined),
                    )
                val initial = fixture.request(plan, 100, resultLimit = limit)
                val returned = drain(service, initial)
                assertEquals(names, returned)
                assertEquals(names.indices.toList(), discovered)
                assertEquals(names.indices.toList(), refined)
            }
        }

    private fun recordingRefinement(
        fixture: QueryServiceTest,
        refined: MutableList<Int>,
    ): io.github.amichne.kast.symbol.contract.SymbolExactOperations = fixture.exactOperations { selection ->
        val position =
            (selection.candidate.location
                    as io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation.Declaration)
                .offset
                .value
        refined += position
        SymbolResolutionResult.Resolved(
            io.github.amichne.kast.symbol.contract.ResolvedSymbol(fixture.selector(selection))
        )
    }

    private suspend fun drain(
        service: QueryService,
        initial: io.github.amichne.kast.query.contract.QueryExecutionRequest,
    ): List<String> {
        var request = initial
        val returned = mutableListOf<String>()
        repeat(30) {
            when (val result = service.run(request)) {
                is QueryExecutionResult.Complete ->
                    return returned + result.result.symbolRows().map { it.description.name.value }
                is QueryExecutionResult.Qualified -> {
                    returned += result.result.symbolRows().map { it.description.name.value }
                    val continuation =
                        result.continuation as io.github.amichne.kast.query.contract.QueryContinuationState.Resumable
                    request =
                        io.github.amichne.kast.query.contract.QueryExecutionRequest.create(
                                initial.plan,
                                initial.lease,
                                initial.budget,
                                continuation.checkpoint,
                            )
                            .refined()
                }
                is QueryExecutionResult.ImpactRejected -> error("Unexpected impact rejection")
                is QueryExecutionResult.Rejected -> error("Unexpected rejection ${result.reason}")
            }
        }
        error("Fixture did not exhaust thirty bounded pages")
    }

    private fun unfinishedDiscovery(): SymbolDiscoveryOperations = SymbolDiscoveryOperations { child ->
        val one = twoCandidates(listOf(7)).discover(child) as SymbolDiscoveryResult.Discovered
        val batch = (one.outcome as SymbolDiscoveryOutcome.Complete).batch
        SymbolDiscoveryResult.Discovered(
            SymbolDiscoveryOutcome.Qualified(
                batch,
                io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications.from(
                        setOf(SymbolDiscoveryQualification.RESULT_LIMIT_REACHED)
                    )
                    .refined(),
                SymbolDiscoveryProgress.Resumable(
                    SymbolDiscoveryRemainder.fromPendingInput(
                            child,
                            emptyList(),
                            SymbolDiscoveryActiveInput.Scanning(
                                batch.candidates.single().location.file as SymbolDiscoveryFileIdentity.Workspace,
                                SymbolDiscoverySourceOffset.parse(8).refined(),
                            ),
                            SymbolDiscoveryInputRevision.parse(8).refined(),
                            discoveredFiles = SymbolDiscoveryWorkCount.parse(1).refined(),
                        )
                        .refined()
                ),
            )
        )
    }

    private fun twoCandidates(offsets: List<Int> = listOf(7, 8)): SymbolDiscoveryOperations =
        SymbolDiscoveryOperations { child ->
            val candidates = offsets.map { offset ->
                SymbolDiscoveryCandidate.fromBoundary(
                        SymbolDiscoveryKind.CLASS,
                        "PaymentService",
                        child.scope.lease,
                        Path.of("/workspace/services/payments/PaymentService.kt"),
                        "file:///workspace/services/payments/PaymentService.kt",
                        offset,
                    )
                    .refined()
            }
            val ordered = candidates.sortedWith(child.candidateOrder())
            val batch =
                SymbolDiscoveryBatch.create(
                        child,
                        ordered,
                        SymbolDiscoveryByteCount.parse(ordered.sumOf { it.projectedUtf8Size().value }).refined(),
                        SymbolDiscoveryWorkCount.parse(2L).refined(),
                        SymbolDiscoveryTimings(
                            SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                            SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                        ),
                    )
                    .refined()
            SymbolDiscoveryResult.Discovered(SymbolDiscoveryOutcome.Complete(batch))
        }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
