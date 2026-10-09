package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryMethodTraceTest {
    @Test
    fun `method trace preserves adapter paths overrides and occurrence evidence at fixed depth`() = runTest {
        val fixture = QueryServiceTest()
        val basis = fixture.selector(fixture.selection())
        val seed = traceFunction(basis, "execute", 30)
        val adapter = traceFunction(basis, "executeWithCache", 60)
        val client = traceFunction(basis, "client", 90)
        val override = traceFunction(basis, "overrideExecute", 120)
        val reads = mutableListOf<Pair<String, RelationMeaning>>()
        val service = methodTraceService(fixture, MethodTraceSymbols(seed, adapter, client, override), reads)
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

    @Test
    fun `grouped method trace resumes without repeating downstream reads or losing paths`() = runTest {
        val fixture = QueryServiceTest()
        val basis = fixture.selector(fixture.selection())
        val symbols =
            MethodTraceSymbols(
                traceFunction(basis, "execute", 30),
                traceFunction(basis, "executeWithCache", 60),
                traceFunction(basis, "client", 90),
                traceFunction(basis, "overrideExecute", 120),
            )
        val plan = fixture.exactReferencePlan(listOf(symbols.seed), listOf(QueryStepSyntax.Trace()))
        for (grant in listOf(4L, 6L, 100L)) {
            val reads = mutableListOf<Pair<String, RelationMeaning>>()
            val service = methodTraceService(fixture, symbols, reads)
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
                traceFunction(basis, "execute", 30),
                traceFunction(basis, "executeWithCache", 60),
                traceFunction(basis, "client", 90),
                traceFunction(basis, "overrideExecute", 120),
            )
        val reads = mutableListOf<Pair<String, RelationMeaning>>()
        val plan = fixture.exactReferencePlan(listOf(symbols.seed), listOf(QueryStepSyntax.Trace()))
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                methodTraceService(fixture, symbols, reads, incompleteReferences = true)
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
        val first = traceFunction(fixture.selector(fixture.selection()), "executeWithCache", 60)
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
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
