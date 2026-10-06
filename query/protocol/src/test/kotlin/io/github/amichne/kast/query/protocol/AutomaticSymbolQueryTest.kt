package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryPreviewDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.continuationToken
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class AutomaticSymbolQueryTest : AutomaticSymbolQueryCase() {
    @Test
    fun `full response capacity can require retention even when its items fit inline`() = runTest {
        val script = Script(listOf(listOf(row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        var admissions = 0
        val result =
            protocol.executeAutomatically(
                request,
                fixture.authority,
                budget,
                policy(
                    inlinePresentation = {
                        admissions++
                        QueryInlinePresentation.RETENTION_REQUIRED
                    }
                ),
            ) as OperationOutcome.Complete
        assertEquals(1, admissions)
        assertEquals(1, result.evidence.payload.items.values.size)
        assertInstanceOf(QueryPreviewDocument.Inline::class.java, result.evidence.payload.invocation!!.preview)
        val reference = (result.evidence.payload.retention as QueryResultRetention.Retained).reference
        val read =
            protocol.execute(QueryRunRequest.ReadResult.symbols(reference, output = output), fixture.authority, budget)
        assertInstanceOf(OperationOutcome.Complete::class.java, read)
        script.assertDrained()
    }

    @Test
    fun `one invocation matches manual pages including an empty advancing page and repeated rows`() = runTest {
        val automatic = Script(listOf(listOf(row, row), emptyList(), listOf(row)))
        val protocol = CanonicalQueryProtocol(automatic.operations, fixture.references)
        val result =
            protocol.executeAutomatically(request, fixture.authority, budget, policy()) as OperationOutcome.Complete
        assertEquals(3, automatic.calls)
        assertEquals(listOf(99L, 91L, 83L), automatic.grants)
        assertEquals(3, result.evidence.payload.invocation!!.accumulatedRowCount)
        assertEquals(QueryInvocationStop.COMPLETED, result.evidence.payload.invocation!!.stop)
        val manual = Script(listOf(listOf(row, row), emptyList(), listOf(row)))
        val manualProtocol = CanonicalQueryProtocol(manual.operations, fixture.references)
        var action: QueryRunRequest = request
        val rows = mutableListOf<QueryResultItemDocument>()
        while (true) {
            when (val page = manualProtocol.execute(action, fixture.authority, budget)) {
                is OperationOutcome.Complete -> {
                    rows += page.evidence.payload.items.values
                    break
                }
                is OperationOutcome.Qualified -> {
                    rows += page.evidence.payload.items.values
                    action = QueryRunRequest.Resume(page.qualification.progress.continuationToken!!)
                }
                is OperationOutcome.Rejected -> error("Unexpected rejection: ${page.reason}")
            }
        }
        assertEquals(rows, result.evidence.payload.items.values)
        automatic.assertDrained()
        manual.assertDrained()
    }

    @Test
    fun `large complete result has bounded preview and retained reads never execute providers`() = runTest {
        val script = Script(listOf(listOf(row, row), listOf(row, row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.executeAutomatically(request, fixture.authority, budget, policy(1)) as OperationOutcome.Complete
        val payload = result.evidence.payload
        assertEquals(1, payload.items.values.size)
        assertEquals(4, payload.invocation!!.accumulatedRowCount)
        assertInstanceOf(QueryPreviewDocument.Prefix::class.java, payload.invocation!!.preview)
        val reference = (payload.retention as QueryResultRetention.Retained).reference
        var cursor = payload.nextCursor!!
        val rows = payload.items.values.toMutableList()
        do {
            val page =
                protocol.execute(
                    QueryRunRequest.ReadResult.symbols(reference, cursor, output),
                    fixture.authority,
                    budget,
                ) as OperationOutcome.Complete
            rows += page.evidence.payload.items.values
            cursor = page.evidence.payload.nextCursor ?: break
        } while (true)
        assertEquals(4, rows.size)
        assertEquals(4, rows.map { it.rowId }.distinct().size)
        assertEquals(2, script.calls)
        script.assertDrained()
    }

    @Test
    fun `retention failure returns partial coverage and no invented reference`() = runTest {
        val script = Script(listOf(listOf(row, row)))
        val protocol =
            CanonicalQueryProtocol(script.operations, fixture.references, QueryStateStore(maximumBytes = 4096))
        val result =
            protocol.executeAutomatically(request, fixture.authority, budget, policy(1)) as OperationOutcome.Qualified
        assertEquals(QueryInvocationStop.RETENTION_FAILED, result.evidence.payload.invocation!!.stop)
        assertEquals(QueryResultRetention.CapacityExceeded, result.evidence.payload.retention)
        assertTrue(QueryLimitationDocument.RETENTION_LIMIT_REACHED in result.qualification.limitations)
        assertNull(result.qualification.progress.continuationToken)
        script.assertDrained()
    }

    @Test
    fun `stale successor preserves earlier rows and its typed rejection without restarting`() = runTest {
        val script = Script(listOf(listOf(row), listOf(row)), staleAfterFirst = true)
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.executeAutomatically(request, fixture.authority, budget, policy()) as OperationOutcome.Qualified
        assertEquals(1, result.evidence.payload.items.values.size)
        assertEquals(QueryInvocationStop.INVALID_STATE, result.evidence.payload.invocation!!.stop)
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.REFERENCE_STALE),
            result.evidence.payload.invocation!!.failure,
        )
        assertNull(result.qualification.progress.continuationToken)
        script.assertDrained()
    }

    @Test
    fun `replayed checkpoint does not append repeated page rows`() = runTest {
        val script = Script(listOf(listOf(row), listOf(row)), repeatCheckpoint = true)
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.executeAutomatically(request, fixture.authority, budget, policy()) as OperationOutcome.Qualified
        assertEquals(2, script.calls)
        assertEquals(1, result.evidence.payload.items.values.size)
        assertEquals(QueryInvocationStop.NON_ADVANCING, result.evidence.payload.invocation!!.stop)
        assertNull(result.qualification.progress.continuationToken)
        script.assertDrained()
    }

    @Test
    fun `terminal upstream limitation survives preview truncation and complete retained drainage`() = runTest {
        val script = Script(listOf(listOf(row, row), listOf(row, row)), terminal = true)
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.executeAutomatically(request, fixture.authority, budget, policy(1)) as OperationOutcome.Qualified
        assertEquals(QueryInvocationStop.TERMINAL_INCOMPLETE, result.evidence.payload.invocation!!.stop)
        assertTrue(QueryLimitationDocument.RELATION_INCOMPLETE in result.qualification.limitations)
        val reference = (result.evidence.payload.retention as QueryResultRetention.Retained).reference
        val read =
            protocol.execute(
                QueryRunRequest.ReadResult.symbols(reference, output = output),
                fixture.authority,
                budget.copy(resources = budget.resources.copy(resultLimit = ResultLimit.parse(100).refined())),
            ) as OperationOutcome.Qualified
        assertEquals(4, read.evidence.payload.items.values.size)
        assertTrue(QueryLimitationDocument.RELATION_INCOMPLETE in read.qualification.limitations)
        assertNull(read.qualification.progress.continuationToken)
        script.assertDrained()
    }

    @Test
    fun `encoded bytes bound a deterministic prefix independently of row limit`() = runTest {
        val script = Script(listOf(listOf(row, row, row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.executeAutomatically(
                request,
                fixture.authority,
                budget,
                policy(byteLimit = 2),
            ) as OperationOutcome.Complete
        assertEquals(3, result.evidence.payload.invocation!!.accumulatedRowCount)
        assertEquals(2L, result.evidence.payload.invocation!!.preview.encodedBytes)
        assertTrue(result.evidence.payload.items.values.isEmpty())
        val reference = (result.evidence.payload.retention as QueryResultRetention.Retained).reference
        val read =
            protocol.execute(QueryRunRequest.ReadResult.symbols(reference, output = output), fixture.authority, budget)
        assertInstanceOf(OperationOutcome.Complete::class.java, read)
        script.assertDrained()
    }

    @Test
    fun `unobserved work fails closed instead of being treated as zero`() = runTest {
        val script = Script(listOf(listOf(row)), observedWork = false)
        val result =
            CanonicalQueryProtocol(script.operations, fixture.references)
                .executeAutomatically(request, fixture.authority, budget, policy()) as OperationOutcome.Qualified
        assertEquals(QueryInvocationStop.INVALID_STATE, result.evidence.payload.invocation!!.stop)
        assertNull(result.qualification.progress.continuationToken)
        script.assertDrained()
    }

    @Test
    fun `accumulation exceeds the public page collection bound without weakening complete coverage`() = runTest {
        val script = Script(List(11) { List(100) { row } })
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.executeAutomatically(
                request,
                fixture.authority,
                budget.copy(resources = budget.resources.copy(resultLimit = ResultLimit.parse(100).refined())),
                policy(retainedBytes = 64_000_000),
            )
        assertTrue(
            result is OperationOutcome.Complete,
            when (result) {
                is OperationOutcome.Qualified ->
                    "${result.evidence.payload.invocation} ${result.qualification.limitations}; calls=${script.calls}"
                is OperationOutcome.Rejected -> result.reason.toString()
                is OperationOutcome.Complete -> "complete"
            },
        )
        result as OperationOutcome.Complete
        assertEquals(1100, result.evidence.payload.invocation!!.accumulatedRowCount)
        assertTrue(result.evidence.payload.items.values.size <= 100)
        assertTrue(result.evidence.payload.invocation!!.preview.encodedBytes <= 100_000)
        val reference = (result.evidence.payload.retention as QueryResultRetention.Retained).reference
        val last =
            protocol.execute(
                QueryRunRequest.ReadResult.symbols(reference, QueryResultCursor.parse(1000).refined(), output),
                fixture.authority,
                budget.copy(resources = budget.resources.copy(resultLimit = ResultLimit.parse(100).refined())),
            ) as OperationOutcome.Complete
        assertEquals(100, last.evidence.payload.items.values.size)
        assertNull(last.evidence.payload.nextCursor)
        script.assertDrained()
    }
}
