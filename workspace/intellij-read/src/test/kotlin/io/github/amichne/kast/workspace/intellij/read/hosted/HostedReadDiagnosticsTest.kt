@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostedReadDiagnosticsTest {
    @Test
    fun `semantic evaluation classification survives transaction completion and typed receipt encoding`() =
        kotlinx.coroutines.test.runTest {
            for (expected in
                listOf(
                    HostedEvaluationOutcome.COMPLETE,
                    HostedEvaluationOutcome.QUALIFIED,
                    HostedEvaluationOutcome.REJECTED,
                )) {
                val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
                val executor =
                    HostedQueryExecutor(backgroundScope) { limits ->
                        HostedReadDiagnostics({ testScheduler.currentTime * 1_000_000 }, limits, receipts::add)
                    }
                val result =
                    executor.execute(executor.endpoint, outcome = { HostedDiagnosticOutcome.Evaluated(expected) }) {
                        progress ->
                        runHostedReadTransaction(progress, { io.github.amichne.kast.kernel.Refinement.Refined(Unit) }) {
                            7
                        }
                    }
                assertTrue(result is HostedExecution.Completed)
                assertEquals(HostedDiagnosticOutcome.Evaluated(expected), receipts.single().outcome)
                val document =
                    kotlinx.serialization.json.Json.parseToJsonElement(receipts.single().encode())
                        as kotlinx.serialization.json.JsonObject
                assertEquals(kotlinx.serialization.json.JsonPrimitive(3), document.getValue("schemaVersion"))
                val outcome = document.getValue("outcome") as kotlinx.serialization.json.JsonObject
                assertEquals(setOf("type", "outcome"), outcome.keys)
                assertEquals(kotlinx.serialization.json.JsonPrimitive("evaluated"), outcome.getValue("type"))
                assertEquals(kotlinx.serialization.json.JsonPrimitive(expected.name), outcome.getValue("outcome"))
                executor.retire()
                executor.drain()
            }
        }

    @Test
    fun `unexpected failures retain bounded adapter frames without throwable payloads`() {
        val limits =
            (io.github.amichne.kast.kernel.ReadLimits.resolve(
                    mapOf(
                        "KAST_READ_DIAGNOSTIC_FRAMES" to "1",
                        "KAST_READ_DIAGNOSTIC_FAILURES" to "1",
                    )
                ) as io.github.amichne.kast.kernel.Refinement.Refined)
                .value
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostic = HostedReadDiagnostics({ 0L }, limits, receipts::add)
        val failure = IllegalStateException("secret source payload")
        failure.stackTrace =
            arrayOf(
                StackTraceElement("io.github.amichne.kast.Adapter", "read", "secret-source.kt", 9),
                StackTraceElement("io.github.amichne.kast.Other", "read", "secret-source.kt", 10),
                StackTraceElement("external.secret.Source", "call", "secret-source.kt", 11),
            )
        diagnostic.unexpected(IntellijReadUnexpectedFailure.capture(IntellijReadStage.RELATION, failure, limits))
        diagnostic.unexpected(IntellijReadUnexpectedFailure.capture(IntellijReadStage.SOURCE, failure, limits))
        diagnostic.finish(
            HostedDiagnosticOutcome.Rejected(HostedQueryFailure.Platform(HostedPlatformFailureCause.RUNTIME))
        )
        val captured = receipts.single().unexpectedFailures.single()
        assertEquals(listOf("io.github.amichne.kast.Adapter.read:9"), captured.adapterFrames)
        assertEquals("java.lang.IllegalStateException", captured.exceptionType)
        assertFalse(captured.toString().contains("secret"))
        assertSame(limits, receipts.single().limits)
    }

    @Test
    fun `configured outer deadline is enforced and rejection is always observed`() = runTest {
        val limits =
            (io.github.amichne.kast.kernel.ReadLimits.resolve(
                    mapOf(
                        "KAST_READ_DIAGNOSTIC_SCOPE_MILLIS" to "10",
                        "KAST_READ_SEMANTIC_MILLIS" to "10",
                        "KAST_READ_HOST_QUERY_MILLIS" to "10",
                    )
                ) as io.github.amichne.kast.kernel.Refinement.Refined)
                .value
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val executor =
            HostedQueryExecutor(backgroundScope) { policy ->
                HostedReadDiagnostics({ testScheduler.currentTime * 1_000_000 }, policy, receipts::add)
            }
        val result =
            executor.execute(executor.endpoint, limits) {
                delay(11)
                1
            }
        assertEquals(HostedExecution.Rejected(HostedQueryFailure.BUDGET_EXCEEDED), result)
        assertEquals(10_000_000, receipts.single().durationNanos)
        executor.retire()
        executor.drain()
        assertTrue(executor.execute(executor.endpoint, limits) { 2 } is HostedExecution.Rejected)
        assertEquals(2, receipts.size)
    }

    @Test
    fun `outer deadline can reject before a later inner budget expires and owner recovers`() = runTest {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val executor =
            HostedQueryExecutor(backgroundScope) {
                HostedReadDiagnostics({ testScheduler.currentTime * 1_000_000 }, publish = receipts::add)
            }
        var innerCompleted = false
        val result =
            executor.execute(executor.endpoint) { progress ->
                progress.advance(HostedQueryStage.MODEL_CAPTURE)
                delay(600)
                progress.advance(HostedQueryStage.SEMANTIC_READ)
                // Mechanism proof using virtual scheduling; not native reproduction evidence.
                delay(1_800)
                innerCompleted = true
                42
            }
        assertFalse(innerCompleted)
        assertEquals(
            HostedExecution.Rejected(HostedQueryFailure.BUDGET_EXCEEDED, HostedQueryStage.SEMANTIC_READ),
            result,
        )
        val receipt = receipts.single()
        assertEquals(HostedSemanticEntry.Entered(1_400_000_000), receipt.semanticEntry)
        assertEquals(2_000_000_000, receipt.durationNanos)
        assertEquals(HostedDiagnosticOutcome.Rejected(HostedQueryFailure.BUDGET_EXCEEDED), receipt.outcome)
        assertEquals(HostedExecution.Completed(7), executor.execute(executor.endpoint) { 7 })
        assertEquals(HostedDiagnosticOutcome.Completed, receipts.last().outcome)
        executor.retire()
        executor.drain()
    }

    @Test
    fun `admission rejection has no invented semantic entry or successful outcome`() = runTest {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val executor =
            HostedQueryExecutor(backgroundScope) {
                HostedReadDiagnostics({ testScheduler.currentTime * 1_000_000 }, publish = receipts::add)
            }
        executor.execute(executor.endpoint) { progress ->
            progress.advance(HostedQueryStage.MODEL_CAPTURE)
            progress.observation.count(IntellijReadCounter.IDEA_MODULES, amount = 259)
            progress.observation.terminated(IntellijReadTermination.MODULE_ADMISSION_LIMIT)
            HostedSemanticRead.Rejected(
                HostedQueryFailure.NamedSourceScope(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
            )
        }
        assertEquals(HostedSemanticEntry.NotEntered, receipts.single().semanticEntry)
        assertEquals(
            HostedDiagnosticOutcome.Rejected(
                HostedQueryFailure.NamedSourceScope(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
            ),
            receipts.single().outcome,
        )
        assertEquals(259, receipts.single().counters.single().count)
        executor.retire()
        executor.drain()
    }

    @Test
    fun `diagnostics saturate counters deduplicate reasons and publish once`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val observation = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        repeat(3) {
            observation.count(IntellijReadCounter.NAMES_VISITED, IntellijReadContributor.KOTLIN_CLASS, Int.MAX_VALUE)
            observation.terminated(IntellijReadTermination.NAME_CAP, IntellijReadContributor.KOTLIN_CLASS)
        }
        observation.finish(HostedDiagnosticOutcome.Completed)
        observation.count(IntellijReadCounter.NAMES_VISITED)
        observation.finish(HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CANCELLED))
        assertEquals(HostedReadDiagnostics.MAX_COUNT, receipts.single().counters.single().count)
        assertEquals(
            listOf(HostedNativeTermination(IntellijReadTermination.NAME_CAP, IntellijReadContributor.KOTLIN_CLASS)),
            receipts.single().terminations,
        )
        assertEquals(HostedDiagnosticOutcome.Completed, receipts.single().outcome)
    }
}
