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
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
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
                    QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
                )
            ) as QueryPlanAdmission.Admitted
        assertTrue(service.run(fixture.request(plan.plan, workLimit = 8L)) is QueryExecutionResult.Complete)
        assertEquals(1, requests.size)
        assertEquals(SymbolDiscoveryTarget.All(SymbolNameDiscoveryKind.SYMBOL), requests.single().target)
        assertEquals(kinds, requests.single().constraints.declarationKinds!!.values.toSet())
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
