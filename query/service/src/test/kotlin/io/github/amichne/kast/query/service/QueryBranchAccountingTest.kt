package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryBranch
import io.github.amichne.kast.query.contract.QueryBranchAccounting
import io.github.amichne.kast.query.contract.QueryBranchObservation
import io.github.amichne.kast.query.contract.QueryBranchOutcome
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QueryWorkUsage
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadRejection
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryBranchAccountingTest {
    @Test
    fun `branch timing preserves the scoped read and explicit rejection`() = runTest {
        for (reject in listOf(false, true)) assertBranchTiming(reject)
    }

    private suspend fun assertBranchTiming(reject: Boolean) {
        val fixture = QueryServiceTest()
        val seed = traceFunction(fixture.selector(fixture.selection()), "execute", 30)
        var now = 0L
        val receipts = mutableListOf<QueryBranchAccounting>()
        val service =
            traceService(
                fixture = fixture,
                relations =
                    RelationOperations { request ->
                        now += branchDuration(request.meaning)
                        if (reject) RelationReadResult.Rejected(RelationReadRejection.COMPILER_IDENTITY_UNAVAILABLE)
                        else {
                            val complete = RelationCompilation.complete(traceBatch(request, emptyList()))
                            RelationReadResult.Complete(complete.batch, complete.coverage)
                        }
                    },
                branchObservation = QueryBranchObservation(receipts::add),
                clock = QueryNanoClock { now },
            )
        val plan =
            fixture.exactReferencePlan(
                listOf(seed),
                listOf(QueryStepSyntax.Trace(RelationSearchBoundary.RETAINED_SUBJECT)),
            )
        val request = fixture.request(plan, 1024L, resultLimit = 100, returnedBytes = 1_000_000L)
        val expectedResult =
            if (reject) QueryExecutionResult.Qualified::class.java else QueryExecutionResult.Complete::class.java
        assertInstanceOf(expectedResult, service.run(request))
        assertEquals(listOf(11L, 3L, 5L), receipts.map { it.elapsed.inWholeMilliseconds })
        val expected = if (reject) QueryBranchOutcome.REJECTED else QueryBranchOutcome.COMPLETE
        assertTrue(receipts.all { it.outcome == expected })
        assertTrue(receipts.all { it.source.fingerprint == RelationEndpoint.subject(seed).fingerprint })
        assertTrue(
            receipts.all { it.expansion == RelationSearchBoundary.RETAINED_SUBJECT && it.effectiveScope == seed.scope }
        )
        if (reject) assertTrue(receipts.all { it.examinedWorkUnits == QueryWorkUsage.Unobserved })
    }

    private fun branchDuration(meaning: RelationMeaning): Long =
        when (meaning) {
            RelationMeaning.References -> 11_000_000L
            RelationMeaning.Callers -> 3_000_000L
            RelationMeaning.Overrides -> 5_000_000L
            else -> error("Unexpected branch")
        }

    @Test
    fun `branch accounting observes defects and cancellation without replacing them`() {
        val cases =
            listOf(
                QueryBranchOutcome.FAILED to IllegalStateException("fixture adapter defect"),
                QueryBranchOutcome.CANCELLED to CancellationException("fixture cancellation"),
            )
        for ((outcome, failure) in cases) {
            val fixture = QueryServiceTest()
            val seed = traceFunction(fixture.selector(fixture.selection()), "execute", 30)
            val receipts = mutableListOf<QueryBranchAccounting>()
            var now = 0L
            val service =
                traceService(
                    fixture = fixture,
                    relations =
                        RelationOperations {
                            now = 11_000_000L
                            throw failure
                        },
                    branchObservation = QueryBranchObservation(receipts::add),
                    clock = QueryNanoClock { now },
                )
            val plan = fixture.exactReferencePlan(listOf(seed), listOf(QueryStepSyntax.Trace()))
            val request = fixture.request(plan, 1024L, resultLimit = 100, returnedBytes = 1_000_000L)
            val thrown = assertThrows(failure.javaClass) { runTest { service.run(request) } }
            assertSame(failure, thrown)
            val receipt = receipts.single()
            assertEquals(outcome, receipt.outcome)
            assertEquals(11L, receipt.elapsed.inWholeMilliseconds)
            assertEquals(0L, receipt.chargedWorkUnits.value)
            assertEquals(QueryWorkUsage.Unobserved, receipt.examinedWorkUnits)
        }
    }

    @Test
    fun `trace attributes each branch without charging shared work twice`() = runTest {
        for (incomplete in listOf(false, true)) assertTraceAccounting(incomplete)
    }

    private suspend fun assertTraceAccounting(incomplete: Boolean) {
        val fixture = QueryServiceTest()
        val basis = fixture.selector(fixture.selection())
        val symbols =
            MethodTraceSymbols(
                traceFunction(basis, "execute", 30),
                traceFunction(basis, "executeWithCache", 60),
                traceFunction(basis, "client", 90),
                traceFunction(basis, "overrideExecute", 120),
            )
        val receipts = mutableListOf<QueryBranchAccounting>()
        val service =
            methodTraceService(
                fixture,
                symbols,
                mutableListOf(),
                incompleteReferences = incomplete,
                branchObservation = QueryBranchObservation(receipts::add),
            )
        val plan = fixture.exactReferencePlan(listOf(symbols.seed), listOf(QueryStepSyntax.Trace()))
        val result = service.run(fixture.request(plan, 1024L, resultLimit = 100, returnedBytes = 1_000_000L))
        assertEquals(6, receipts.size)
        assertEquals(
            listOf(2L, 1L, 1L, 0L, 1L, 1L),
            receipts.map { assertInstanceOf(QueryWorkUsage.Observed::class.java, it.examinedWorkUnits).count.value },
        )
        assertEquals(7L, receipts.sumOf { it.chargedWorkUnits.value })
        assertEquals(8L, assertInstanceOf(QueryWorkUsage.Observed::class.java, result.workUsage).count.value)
        assertEquals(
            listOf(
                QueryBranch.REFERENCES,
                QueryBranch.CALLERS,
                QueryBranch.OVERRIDES,
                QueryBranch.REFERENCES,
                QueryBranch.CALLERS,
                QueryBranch.CALLERS,
            ),
            receipts.map { it.branch },
        )
        assertEquals(listOf(2, 1, 1, 0, 1, 1), receipts.map { it.emittedFacts.value })
        assertEquals(
            if (incomplete) QueryBranchOutcome.QUALIFIED else QueryBranchOutcome.COMPLETE,
            receipts.first().outcome,
        )
        assertTrue(receipts.drop(1).all { it.outcome == QueryBranchOutcome.COMPLETE })
        assertTrue(receipts.all { it.source.lease == symbols.seed.lease && it.reads == 1 })
        assertTrue(receipts.all { it.effectiveScope == symbols.seed.scope })
    }
}
