package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpointStorageOutcome
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOccurrence
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryTerminalReason
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real service, runner, state and publication boundaries under unchanged invocation grants. */
internal class AutomaticDenseRetentionTest : AutomaticDenseRetentionCase() {
    @Test
    fun `dense accounting preserves finite capacity rejection under unchanged grants`() = runTest {
        val state = QueryStateStore(clock = { 0L })
        val ledger = mutableListOf<QueryInvocationRetentionAdmission>()
        val base = policy(rows = 20, byteLimit = 32_768, retainedBytes = QueryByteLimit.DefaultCheckpoint.value)
        val invocationPolicy =
            QueryInvocationPolicy(
                base.previewRows,
                base.previewBytesLimit,
                base.retainedBytes,
                base.previewBytes,
                nanoTime = { 0L },
                retentionObservation = QueryInvocationRetentionObservation(ledger::add),
            )
        val recording = QueryInvocationExecution(service)
        val protocol = CanonicalQueryProtocol(recording, fixture.references, state)
        val runner =
            AutomaticSymbolQueryRunner(state, fixture.authority, invocationPolicy) { action, remaining ->
                recording.page(remaining) { protocol.executePage(action, fixture.authority, remaining) }
            }
        val accumulated = runner.run(input, grant).refined()
        val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, accumulated.execution)
        assertEquals(QueryInvocationStop.TERMINAL_INCOMPLETE, accumulated.stop)
        assertEquals(
            QueryContinuationState.Terminal(QueryTerminalReason.CHECKPOINT_CAPACITY_EXCEEDED),
            qualified.continuation,
        )
        val rows = assertInstanceOf(QueryRows.Occurrences::class.java, qualified.result.rows).values
        val starts = rows.map { (it as QueryOccurrence.Reference).value.occurrence.range.startInclusive }
        assertEquals(starts.size, starts.distinct().size)
        assertTrue(starts.isNotEmpty() && starts.size < locators.size)
        assertTrue(locators.map { it.range.startInclusive }.containsAll(starts))
        assertEquals(starts.size, qualified.coverage.knownMinimum.value)
        assertEquals((0 until positions.size).map { it * 20L }, positions)
        assertEquals(QueryCheckpointStorageOutcome.CAPACITY_EXCEEDED, checkpointAdmissions.last().outcome)
        assertTrue(checkpointAdmissions.all { it.allowance.value <= grant.checkpointBytes.value })
        assertTrue(ledger.all { it.total.value <= invocationPolicy.retainedBytes.value })
        assertTrue(ledger.all { it.total.value == it.facts.value + it.issuedState.value + it.request.value })
        assertTrue(ledger.all { it.request.value == input.accountedRequestBytes() })

        assertTrue(ledger.any { it.issuedState.value > 0L }, "Replay owners must remain charged")
    }

    @Test
    fun `dense capacity rejection retains an evidence only prefix without replaying providers`() = runTest {
        val state = QueryStateStore(clock = { 0L })
        val protocol = CanonicalQueryProtocol(service, fixture.references, state)
        val rejection =
            completionRejection(
                protocol.execute(
                    input,
                    fixture.authority,
                    grant,
                    policy(rows = 20, byteLimit = 32_768, retainedBytes = QueryByteLimit.DefaultCheckpoint.value),
                )
            )
        val retained = retainedEvidence(rejection)
        val known = originalCoverage(rejection).knownMinimum.value
        assertTrue(known > 0 && known < locators.size)
        val providerCalls = positions.toList()
        val firstRequest = QueryRunRequest.ReadResult.occurrences(retained.result, QueryResultCursor.parse(0).refined())
        val first = protocol.executePage(firstRequest, fixture.authority, grant)
        assertEquals(first, protocol.executePage(firstRequest, fixture.authority, grant))
        val items = mutableListOf<QueryResultItemDocument>()
        var next: QueryPublishedPage = first
        repeat(locators.size) {
            val qualified = assertInstanceOf(OperationOutcome.Qualified::class.java, next)
            val payload = qualified.evidence.payload as QueryRunResult
            val interpretation =
                assertInstanceOf(QueryResultInterpretationDocument.EvidenceOnly::class.java, payload.interpretation)
            assertEquals(rejection.originalCoverage, interpretation.originalCoverage)
            items += payload.items.values
            val cursor = payload.nextCursor
            if (cursor == null) {
                assertEquals(known, items.size)
                assertEquals(items.size, items.map { it.rowId }.distinct().size)
                assertEquals(providerCalls, positions)
                return@runTest
            }
            next =
                protocol.executePage(
                    QueryRunRequest.ReadResult.occurrences(retained.result, cursor),
                    fixture.authority,
                    grant,
                )
        }
        error("Finite retained prefix exceeded the independent inventory ceiling")
    }

    @Test
    fun `production facts charge a shared inventory owner once across a matched page prefix`() = runTest {
        val state = QueryStateStore(clock = { 0L })
        val recording = QueryInvocationExecution(service)
        val protocol = CanonicalQueryProtocol(recording, fixture.references, state)
        val first =
            SymbolInvocationPage.admit(
                    recording.page(grant) { protocol.executePage(input, fixture.authority, grant) },
                    input,
                )
                .refined()
        val second =
            SymbolInvocationPage.admit(
                    recording.page(grant) {
                        protocol.executePage(
                            QueryRunRequest.Resume(requireNotNull(first.issuedToken)),
                            fixture.authority,
                            grant,
                        )
                    },
                    input,
                )
                .refined()
        val invocationPolicy = policy()
        val facts = QueryInvocationFacts(fixture.authority, invocationPolicy)
        facts.append(first, Long.MAX_VALUE).refined()
        facts.append(second, Long.MAX_VALUE).refined()
        val standalone =
            listOf(first, second).sumOf { page ->
                QueryRetainedResult.capture(fixture.authority, page.execution).refined().retainedBytes +
                    invocationPolicy.previewBytes(page.items) +
                    page.items.size * QUERY_ROW_REFERENCE_CHARGE_BYTES
            }
        val starts =
            listOf(first, second)
                .flatMap { (it.result.rows as QueryRows.Occurrences).values }
                .take(40)
                .map { (it as QueryOccurrence.Reference).value.occurrence.range.startInclusive }
        println(
            "MatchedPrefixFacts(bytes=${facts.retainedBytes}, standalone=$standalone, " +
                "inventory=${inventory.retainedBytes}, " +
                "providerPositions=${positions.take(2)}, rows=${listOf(first, second).map { it.items.size }}, " +
                "work=${listOf(first, second).map { it.work.count.value }}, " +
                "occurrenceStarts=$starts)"
        )
        assertTrue(
            facts.retainedBytes < standalone - inventory.retainedBytes / 2,
            "Matched two-page owner charges: facts=${facts.retainedBytes}, " +
                "standalone=$standalone, inventory=${inventory.retainedBytes}",
        )
    }

    @Test
    fun `rejected page admission does not commit shared owners or lose non advancing proof`() = runTest {
        val state = QueryStateStore(clock = { 0L })
        val recording = QueryInvocationExecution(service)
        val protocol = CanonicalQueryProtocol(recording, fixture.references, state)
        val observed = recording.page(grant) { protocol.executePage(input, fixture.authority, grant) }
        val page = SymbolInvocationPage.admit(observed, input).refined()
        val facts = QueryInvocationFacts(fixture.authority, policy())
        assertEquals(
            QueryInvocationStop.RETAINED_BYTES_LIMIT,
            (facts.append(page, 0L) as Refinement.Rejected).failure.reason,
        )
        assertEquals(0L, facts.retainedBytes)
        facts.append(page, Long.MAX_VALUE).refined()
        val independent = QueryInvocationFacts(fixture.authority, policy())
        independent.append(page, Long.MAX_VALUE).refined()
        assertEquals(independent.retainedBytes, facts.retainedBytes)
        assertEquals(
            QueryInvocationStop.NON_ADVANCING,
            (facts.append(page, Long.MAX_VALUE) as Refinement.Rejected).failure.reason,
        )
    }
}
