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
            protocol.execute(
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
            protocol.executePage(
                QueryRunRequest.ReadResult.symbols(reference, output = output),
                fixture.authority,
                budget,
            )
        assertInstanceOf(OperationOutcome.Complete::class.java, read)
        script.assertDrained()
    }

    @Test
    fun `one invocation matches manual pages including an empty advancing page and repeated rows`() = runTest {
        val automatic = Script(listOf(listOf(row, row), emptyList(), listOf(row)))
        val protocol = CanonicalQueryProtocol(automatic.operations, fixture.references)
        val result = protocol.execute(request, fixture.authority, budget, policy()) as OperationOutcome.Complete
        assertEquals(3, automatic.calls)
        assertEquals(listOf(99L, 91L, 83L), automatic.grants)
        assertEquals(3, result.evidence.payload.invocation!!.accumulatedRowCount)
        assertEquals(QueryInvocationStop.COMPLETED, result.evidence.payload.invocation!!.stop)
        val manual = Script(listOf(listOf(row, row), emptyList(), listOf(row)))
        val manualProtocol = CanonicalQueryProtocol(manual.operations, fixture.references)
        var action: QueryRunRequest = request
        val rows = mutableListOf<QueryResultItemDocument>()
        while (true) {
            when (val page = manualProtocol.executePage(action, fixture.authority, budget)) {
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
        val result = protocol.execute(request, fixture.authority, budget, policy(1)) as OperationOutcome.Complete
        val payload = result.evidence.payload
        assertEquals(1, payload.items.values.size)
        assertEquals(4, payload.invocation!!.accumulatedRowCount)
        assertInstanceOf(QueryPreviewDocument.Prefix::class.java, payload.invocation!!.preview)
        val reference = (payload.retention as QueryResultRetention.Retained).reference
        var cursor = payload.nextCursor!!
        val rows = payload.items.values.toMutableList()
        do {
            val page =
                protocol.executePage(
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
    fun `retention failure rejects completion with original complete coverage and no invented reference`() = runTest {
        val script = Script(listOf(listOf(row, row)))
        val protocol =
            CanonicalQueryProtocol(script.operations, fixture.references, QueryStateStore(maximumBytes = 4096))
        val rejection = completionRejection(protocol.execute(request, fixture.authority, budget, policy(1)))
        assertEquals(QueryInvocationStop.RETENTION_FAILED, rejection.stop)
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryCompletionUnprovenReason.RETENTION_UNAVAILABLE,
            rejection.reason,
        )
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument.RetentionUnavailable(
                io.github.amichne.kast.protocol.contract.QueryCompletionRetentionFailure.CAPACITY_EXCEEDED
            ),
            rejection.cause,
        )
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument.Complete,
            rejection.originalCoverage,
        )
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument.Unavailable(
                io.github.amichne.kast.protocol.contract.QueryCompletionRetentionFailure.CAPACITY_EXCEEDED
            ),
            rejection.evidence,
        )
        script.assertDrained()
    }

    @Test
    fun `stale successor rejects completion and preserves earlier rows and original failure without restarting`() =
        runTest {
            val script = Script(listOf(listOf(row), listOf(row)), staleAfterFirst = true)
            val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
            val rejection = completionRejection(protocol.execute(request, fixture.authority, budget, policy()))
            assertEquals(1, retainedEvidence(rejection).preview.values.size)
            assertEquals(QueryInvocationStop.INVALID_STATE, rejection.stop)
            assertEquals(
                QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.REFERENCE_STALE),
                rejection.originalFailure,
            )
            val read =
                protocol.executePage(retainedEvidence(rejection).readRequest(), fixture.authority, budget)
                    as OperationOutcome.Qualified
            assertEquals(1, read.evidence.payload.items.values.size)
            assertNull(read.qualification.progress.continuationToken)
            script.assertDrained()
        }

    @Test
    fun `replayed checkpoint rejects completion without appending repeated page rows`() = runTest {
        val script = Script(listOf(listOf(row), listOf(row)), repeatCheckpoint = true)
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val rejection = completionRejection(protocol.execute(request, fixture.authority, budget, policy()))
        assertEquals(2, script.calls)
        assertEquals(1, retainedEvidence(rejection).preview.values.size)
        assertEquals(QueryInvocationStop.NON_ADVANCING, rejection.stop)
        val read =
            protocol.executePage(retainedEvidence(rejection).readRequest(), fixture.authority, budget)
                as OperationOutcome.Qualified
        assertEquals(1, read.evidence.payload.items.values.size)
        assertNull(read.qualification.progress.continuationToken)
        script.assertDrained()
    }

    @Test
    fun `terminal upstream limitation survives rejected preview and retained drainage`() = runTest {
        val script = Script(listOf(listOf(row, row), listOf(row, row)), terminal = true)
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val rejection = completionRejection(protocol.execute(request, fixture.authority, budget, policy(1)))
        assertEquals(QueryInvocationStop.TERMINAL_INCOMPLETE, rejection.stop)
        assertTrue(QueryLimitationDocument.RELATION_INCOMPLETE in originalCoverage(rejection).limitations.values)
        val read =
            protocol.executePage(
                retainedEvidence(rejection).readRequest(),
                fixture.authority,
                budget.copy(resources = budget.resources.copy(resultLimit = ResultLimit.parse(100).refined())),
            ) as OperationOutcome.Qualified
        assertEquals(4, read.evidence.payload.items.values.size)
        assertTrue(QueryLimitationDocument.RELATION_INCOMPLETE in read.qualification.limitations)
        assertNull(read.qualification.progress.continuationToken)
        val interpretation =
            read.evidence.payload.interpretation
                as io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument.EvidenceOnly
        assertEquals(rejection.originalCoverage, interpretation.originalCoverage)
        script.assertDrained()
    }

    @Test
    fun `encoded bytes bound a deterministic prefix independently of row limit`() = runTest {
        val script = Script(listOf(listOf(row, row, row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.execute(
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
            protocol.executePage(
                QueryRunRequest.ReadResult.symbols(reference, output = output),
                fixture.authority,
                budget,
            )
        assertInstanceOf(OperationOutcome.Complete::class.java, read)
        script.assertDrained()
    }

    @Test
    fun `unobserved work fails closed instead of being treated as zero`() = runTest {
        val script = Script(listOf(listOf(row)), observedWork = false)
        val result =
            CanonicalQueryProtocol(script.operations, fixture.references)
                .execute(request, fixture.authority, budget, policy())
        val rejection = completionRejection(result)
        assertEquals(QueryInvocationStop.INVALID_STATE, rejection.stop)
        script.assertDrained()
    }

    @Test
    fun `accumulation exceeds the public page collection bound without weakening complete coverage`() = runTest {
        val script = Script(List(11) { List(100) { row } })
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.execute(
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
            protocol.executePage(
                QueryRunRequest.ReadResult.symbols(reference, QueryResultCursor.parse(1000).refined(), output),
                fixture.authority,
                budget.copy(resources = budget.resources.copy(resultLimit = ResultLimit.parse(100).refined())),
            ) as OperationOutcome.Complete
        assertEquals(100, last.evidence.payload.items.values.size)
        assertNull(last.evidence.payload.nextCursor)
        script.assertDrained()
    }
}
