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
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.symbol.contract.candidateOrder
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryDiscoveryPlanningTest {
    @Test
    fun `mixed declaration families use one symbol discovery pass with all constraints retained`() = runTest {
        val kinds =
            setOf(
                CompilerSymbolKind.CLASSLIKE,
                CompilerSymbolKind.FUNCTION,
                CompilerSymbolKind.PROPERTY,
                CompilerSymbolKind.TYPE_ALIAS,
            )
        val syntax =
            QueryDiscoverySyntax(QueryMatch.All, QueryScope.Unrestricted, QueryDeclarationKinds.from(kinds).refined())
        val requests = mutableListOf<SymbolDiscoveryRequest>()
        val fixture = QueryServiceTest()
        val service =
            fixture.service(
                discovery =
                    SymbolDiscoveryOperations { child ->
                        requests += child
                        fixture.discoveryEmpty(qualified = false).discover(child)
                    }
            )
        val plan =
            QueryPlanCompiler.admit(
                QueryPlanSyntax(
                    QuerySourceSyntax.Symbols(syntax),
                    emptyList(),
                    QueryOutputSyntax(QuerySymbolFields.from(emptySet()).refined()),
                )
            ) as QueryPlanAdmission.Admitted
        assertTrue(service.run(fixture.request(plan.plan, workLimit = 8L)) is QueryExecutionResult.Complete)
        assertEquals(1, requests.size)
        assertEquals(SymbolDiscoveryTarget.All(SymbolNameDiscoveryKind.SYMBOL), requests.single().target)
        assertEquals(kinds, requests.single().constraints.declarationKinds!!.values.toSet())
    }

    @Test
    fun `distinct candidate selections resolving one canonical symbol require explicit distinct`() = runTest {
        val fixture = QueryServiceTest()
        val selected = fixture.selector(fixture.selection())
        var refinements = 0
        val service =
            fixture.service(
                discovery = twoCandidates(),
                exact =
                    fixture.exactOperations { _ ->
                        refinements++
                        SymbolResolutionResult.Resolved(io.github.amichne.kast.symbol.contract.ResolvedSymbol(selected))
                    },
            )
        val syntax =
            QueryDiscoverySyntax(
                QueryMatch.All,
                QueryScope.Unrestricted,
                QueryDeclarationKinds.from(setOf(CompilerSymbolKind.CLASSLIKE)).refined(),
            )
        fun plan(steps: List<QueryStepSyntax>) =
            (QueryPlanCompiler.admit(
                    QueryPlanSyntax(
                        QuerySourceSyntax.Symbols(syntax),
                        steps,
                        QueryOutputSyntax(QuerySymbolFields.from(emptySet()).refined()),
                    )
                ) as QueryPlanAdmission.Admitted)
                .plan

        val repeated = service.run(fixture.request(plan(emptyList()), workLimit = 8L))
        val distinct = service.run(fixture.request(plan(listOf(QueryStepSyntax.Distinct)), workLimit = 8L))
        assertEquals(2, (repeated as QueryExecutionResult.Complete).result.items.size)
        assertEquals(1, (distinct as QueryExecutionResult.Complete).result.items.size)
        assertEquals(4, refinements)
    }

    private fun twoCandidates(): SymbolDiscoveryOperations = SymbolDiscoveryOperations { child ->
        val candidates =
            listOf(7, 8).map { offset ->
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
