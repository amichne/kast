package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryRelationCoverage
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QueryWorkUsage
import io.github.amichne.kast.relation.contract.RelationMeaning
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Identical production-rule workload on either revision; external relation observations are scripted. */
class QueryTraceWorkTest {
    @Test
    fun `matched trace and one hop workloads preserve independent witnesses`() = runTest {
        for (case in cases()) measure(case.path, case.size, case.grant)
    }

    private enum class WorkPath {
        TRACE,
        REFERENCES,
        CALLERS,
        EXACT,
    }

    private data class WorkCase(val path: WorkPath, val size: Int, val grant: Long)

    private fun cases() =
        listOf(
            WorkCase(WorkPath.TRACE, 2, 4L),
            WorkCase(WorkPath.TRACE, 2, 6L),
            WorkCase(WorkPath.TRACE, 2, 1024L),
            WorkCase(WorkPath.TRACE, 8, 1024L),
            WorkCase(WorkPath.TRACE, 32, 1024L),
            WorkCase(WorkPath.REFERENCES, 2, 4L),
            WorkCase(WorkPath.REFERENCES, 2, 1024L),
            WorkCase(WorkPath.REFERENCES, 8, 1024L),
            WorkCase(WorkPath.REFERENCES, 32, 1024L),
            WorkCase(WorkPath.CALLERS, 2, 4L),
            WorkCase(WorkPath.CALLERS, 2, 1024L),
            WorkCase(WorkPath.EXACT, 2, 4L),
            WorkCase(WorkPath.EXACT, 2, 1024L),
        )

    private suspend fun measure(path: WorkPath, size: Int, grant: Long) {
        val fixture = QueryServiceTest()
        val basis = fixture.selector(fixture.selection())
        val symbols =
            MethodTraceSymbols(
                traceFunction(basis, "execute", 30),
                traceFunction(basis, "executeWithCache", 60),
                traceFunction(basis, "client", 90),
                traceFunction(basis, "overrideExecute", 120),
            )
        val starts = (300 until 300 + size).toList()
        val reads = mutableListOf<Pair<String, RelationMeaning>>()
        val service = methodTraceService(fixture, symbols, reads, referenceStarts = starts)
        val steps =
            when (path) {
                WorkPath.TRACE -> listOf(QueryStepSyntax.Trace())
                WorkPath.REFERENCES -> listOf(QueryStepSyntax.Related(RelationMeaning.References))
                WorkPath.CALLERS -> listOf(QueryStepSyntax.Related(RelationMeaning.Callers))
                WorkPath.EXACT -> emptyList()
            }
        val plan = fixture.exactReferencePlan(listOf(symbols.seed), steps)
        val request = fixture.request(plan, grant, resultLimit = 100, returnedBytes = 1_000_000L)
        val output = drain(service, request)
        assertWitness(path, starts, symbols, output.rows, reads)
        val downstream = reads.count { it == "executeWithCache" to RelationMeaning.Callers }
        println(
            "QUERY_WORK ${path.name} $size $grant ${output.pages} ${reads.size} $downstream " +
                "${output.work} ${output.rows.size} ${output.duplicates} ${output.digest} ${output.rawDigest}"
        )
    }

    private data class WorkOutput(
        val rows: List<QuerySymbol>,
        val pages: Int,
        val work: Long,
        val digest: String,
        val rawDigest: String,
        val duplicates: Int,
    )

    private suspend fun drain(service: QueryService, request: QueryExecutionRequest): WorkOutput {
        var page = service.run(request)
        var pages = 1
        var work = 0L
        val rows = mutableListOf<QuerySymbol>()
        val questions = mutableSetOf<String>()
        while (true) {
            work += assertInstanceOf(QueryWorkUsage.Observed::class.java, page.workUsage).count.value
            val snapshot =
                when (page) {
                    is QueryExecutionResult.Complete -> page.result
                    is QueryExecutionResult.Qualified -> page.result
                    else -> error("Unexpected workload rejection: $page")
                }
            rows += snapshot.symbolRows()
            questions += exhaustiveQuestions(snapshot)
            if (page is QueryExecutionResult.Complete) break
            val checkpoint =
                assertInstanceOf(
                    QueryContinuationState.Resumable::class.java,
                    (page as QueryExecutionResult.Qualified).continuation,
                )
            assertTrue(++pages <= 100, "Every admitted workload must drain")
            page =
                service.run(
                    QueryExecutionRequest.create(request.plan, request.lease, request.budget, checkpoint.checkpoint)
                        .refined()
                )
        }
        val raw = rowFacts(rows, unique = false) + questions.sorted()
        val canonical = rowFacts(rows, unique = true) + questions.sorted()
        val duplicates = rows.sumOf { row ->
            val facts = row.connections.map { it.canonicalProjection() }
            facts.size - facts.toSet().size
        }
        return WorkOutput(rows, pages, work, digest(canonical), digest(raw), duplicates)
    }

    /** Canonically identical facts repeat the same proof; preserve and count raw duplicates separately. */
    private fun rowFacts(rows: List<QuerySymbol>, unique: Boolean): List<String> =
        rows
            .map {
                val raw = it.connections.map { fact -> fact.canonicalProjection() }
                val selected = if (unique) raw.distinct() else raw
                "${it.selector.fingerprint.value}:${selected.sorted()}"
            }
            .sorted()

    private fun digest(records: List<String>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(records.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun exhaustiveQuestions(snapshot: QueryResult): List<String> {
        assertTrue(snapshot.failures.isEmpty())
        assertTrue(snapshot.omissions.isEmpty())
        assertTrue(snapshot.walkObservations.isEmpty())
        return snapshot.relationObservations.map {
            assertEquals(QueryRelationCoverage.Exhausted, it.coverage)
            assertTrue(
                it.scopeExclusions.isEmpty() && it.callbackObservations.isEmpty() && it.callableObservations.isEmpty()
            )
            "${it.question.subject.fingerprint.value}:${it.question.meaning}:${it.question.domainFingerprint.value}"
        }
    }

    private fun assertWitness(
        path: WorkPath,
        starts: List<Int>,
        symbols: MethodTraceSymbols,
        rows: List<QuerySymbol>,
        reads: List<Pair<String, RelationMeaning>>,
    ) {
        val expectedNames =
            when (path) {
                WorkPath.TRACE -> setOf("execute", "executeWithCache", "client", "overrideExecute")
                WorkPath.REFERENCES,
                WorkPath.CALLERS -> setOf("executeWithCache")
                WorkPath.EXACT -> setOf("execute")
            }
        val expectedStarts =
            when (path) {
                WorkPath.TRACE -> starts.toSet() + setOf(210, 220, 230, 240)
                WorkPath.REFERENCES -> starts.toSet()
                WorkPath.CALLERS -> setOf(220)
                WorkPath.EXACT -> emptySet()
            }
        assertEquals(expectedNames, rows.map { it.description.name.value }.toSet())
        assertEquals(expectedStarts, rows.flatMap { it.connections }.map { it.occurrence.range.startInclusive }.toSet())
        val expectedRows =
            when (path) {
                WorkPath.TRACE -> 4
                WorkPath.REFERENCES -> starts.size
                else -> 1
            }
        assertEquals(expectedRows, rows.size)
        assertTrue(
            rows.all { row ->
                row.selector.lease == symbols.seed.lease &&
                    row.connections.all { it.authority == symbols.seed.lease.identity }
            }
        )
        if (path == WorkPath.TRACE) {
            assertEquals(
                expectedStarts,
                rows
                    .single { it.description.name.value == "client" }
                    .connections
                    .map { it.occurrence.range.startInclusive }
                    .toSet(),
            )
            assertFalse(reads.any { it.first == "client" }, "Fixed depth must hold")
        }
    }
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
