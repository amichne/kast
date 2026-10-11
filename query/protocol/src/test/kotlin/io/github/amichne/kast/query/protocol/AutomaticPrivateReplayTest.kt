package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.continuationToken
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.symbol.contract.SymbolDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class AutomaticPrivateReplayTest : AutomaticSymbolQueryCase() {
    @Test
    fun `public pages retain immutable replay allocations after the same three semantic calls`() = runTest {
        val store = QueryStateStore(clock = { 0L })
        val script = Script(listOf(listOf(row), emptyList(), listOf(row)))
        val recording = QueryInvocationExecution(script.operations)
        val protocol = CanonicalQueryProtocol(recording, fixture.references, store)
        val pages = mutableListOf<QueryPublishedPage>()
        val runner =
            AutomaticSymbolQueryRunner(store, fixture.authority, policy()) { action, remaining ->
                recording.page(remaining) {
                    protocol.executePage(action, fixture.authority, remaining).also(pages::add)
                }
            }
        val accumulated = runner.run(request, budget).refined()
        assertEquals(QueryInvocationStop.COMPLETED, accumulated.stop)
        assertEquals(2, accumulated.items.size)
        assertEquals(accumulated.items.first(), accumulated.items.last())
        assertEquals(2, store.retentionMeasurements().retainedEntries.value)
        assertTrue(store.retentionMeasurements().retainedBytes.value > 0)
        val beforeReplay = store.retentionMeasurements().retainedBytes
        pages.zipWithNext().forEach { (from, page) ->
            val token = (from as OperationOutcome.Qualified).qualification.progress.continuationToken!!
            assertEquals(page, protocol.executePage(QueryRunRequest.Resume(token), fixture.authority, budget))
        }
        assertEquals(beforeReplay, store.retentionMeasurements().retainedBytes)
        println("PublicReplayRetained(entries=2, bytes=${beforeReplay.value}, calls=${script.calls})")
        script.assertDrained()
    }

    @Test
    fun `automatic completion releases private replay owners after three pages`() = runTest {
        val store = QueryStateStore(clock = { 0L })
        val script = Script(listOf(listOf(row), emptyList(), listOf(row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references, store)
        val result = protocol.execute(request, fixture.authority, budget, policy()) as OperationOutcome.Complete
        assertEquals(QueryInvocationStop.COMPLETED, result.evidence.payload.invocation!!.stop)
        assertEquals(2, result.evidence.payload.items.values.size)
        assertEquals(result.evidence.payload.items.values.first(), result.evidence.payload.items.values.last())
        result.evidence.payload.items.values.forEach { item ->
            val symbol = item as QueryResultItemDocument.ExactSymbol
            val restored =
                fixture.references.restoreExact(symbol.ref.token, fixture.authority)
                    as CanonicalSelectorDecoding.Decoded
            assertEquals(
                SymbolDescription.from(fixture.selector).compilerIdentity,
                SymbolDescription.from(restored.value).compilerIdentity,
            )
        }
        val retained = store.retentionMeasurements()
        assertEquals(0, retained.retainedEntries.value)
        assertEquals(0L, retained.retainedBytes.value)
        println(
            "PrivateReplayReleased(entries=${retained.retainedEntries.value}, " +
                "bytes=${retained.retainedBytes.value}, calls=${script.calls})"
        )
        script.assertDrained()
    }

    @Test
    fun `cooperative cancellation publishes only the final checkpoint with its original expiry`() = runTest {
        var now = 0L
        val store = QueryStateStore(clock = { now }, ttlMillis = 1)
        val script = Script(listOf(listOf(row), emptyList(), listOf(row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references, store)
        val rejection =
            completionRejection(
                protocol.execute(
                    request,
                    fixture.authority,
                    budget,
                    policy(cancelled = { script.calls == 2 }),
                )
            )
        assertEquals(QueryInvocationStop.CANCELLED, rejection.stop)
        assertEquals(1, originalCoverage(rejection).knownMinimum.value)
        val token = originalCoverage(rejection).progress.continuationToken as QueryExecutionContinuation.Pipeline
        assertTrue(store.restoreCheckpoint(token, fixture.authority) is QueryCheckpointRestoration.Restored)
        assertEquals(
            2,
            store.retentionMeasurements().retainedEntries.value,
            "One public checkpoint and one retained prefix",
        )
        val first = protocol.executePage(QueryRunRequest.Resume(token), fixture.authority, budget)
        assertTrue(first is OperationOutcome.Complete)
        assertEquals(first, protocol.executePage(QueryRunRequest.Resume(token), fixture.authority, budget))
        script.assertDrained()
        now = 1_000_001L
        assertEquals(QueryCheckpointRestoration.Unavailable, store.restoreCheckpoint(token, fixture.authority))
        assertEquals(0, store.retentionMeasurements().retainedEntries.value)
    }

    @Test
    fun `hard cancellation rolls back reservation and ends the unpublished checkpoint lifetime`() = runTest {
        val store = QueryStateStore(clock = { 0L })
        val script = Script(listOf(listOf(row), listOf(row)))
        var attempts = 0
        val operations = QueryOperations { execution ->
            attempts++
            if (execution.checkpoint != null) throw CancellationException("owned fixture cancellation")
            script.operations.run(execution)
        }
        val protocol = CanonicalQueryProtocol(operations, fixture.references, store)
        try {
            protocol.execute(request, fixture.authority, budget, policy())
            error("Expected hard cancellation")
        } catch (_: CancellationException) {
            assertEquals(2, attempts)
            assertEquals(1, script.calls)
            assertEquals(0, store.retentionMeasurements().retainedEntries.value)
            assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
        }
    }

    @Test
    fun `repeated automatic reuse releases only its own allocations amid unrelated retained scale`() = runTest {
        val store = QueryStateStore(clock = { 0L })
        lateinit var plan: AdmittedQueryPlan
        val seed = Script(listOf(listOf(row)))
        val capturing = QueryOperations { execution ->
            plan = execution.plan
            seed.operations.run(execution)
        }
        CanonicalQueryProtocol(capturing, fixture.references, store)
            .execute(request, fixture.authority, budget, policy())
        seed.assertDrained()
        val unrelated =
            (100 until 120).map { page ->
                (store.issueCheckpoint(request, Checkpoint(plan, fixture.authority, page))
                        as QueryCheckpointIssuance.Issued)
                    .token
            }
        val before = store.retentionMeasurements().retainedBytes
        repeat(5) {
            val script = Script(listOf(listOf(row), emptyList(), listOf(row)))
            val protocol = CanonicalQueryProtocol(script.operations, fixture.references, store)
            assertTrue(protocol.execute(request, fixture.authority, budget, policy()) is OperationOutcome.Complete)
            assertEquals(20, store.retentionMeasurements().retainedEntries.value)
            assertEquals(before, store.retentionMeasurements().retainedBytes)
            unrelated.forEachIndexed { index, token ->
                val restored = store.restoreCheckpoint(token, fixture.authority) as QueryCheckpointRestoration.Restored
                assertEquals(100 + index, (restored.checkpoint as Checkpoint).page)
            }
            script.assertDrained()
        }
    }
}
