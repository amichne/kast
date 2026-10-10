package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.workspace.intellij.read.IntellijReadActionKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadActionMode
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.attempt
import io.github.amichne.kast.workspace.intellij.read.observeReadAction
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HostedReadActionAccountingTest {
    @Test
    fun `suspension retry and return dispatch remain distinct from native execution`() = runTest {
        var now = 0L
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val entries = mutableListOf<HostedReadActionStarted>()
        val observation = HostedReadDiagnostics({ now }, publishAdmission = entries::add, publish = receipts::add)
        val cancellation = ProcessCanceledException()
        val value =
            observation.observeReadAction(IntellijReadActionKind.RELATION, IntellijReadActionMode.READ) { scope ->
                yield()
                now = 5
                assertSame(
                    cancellation,
                    assertThrows<ProcessCanceledException> {
                        scope.attempt {
                            now = 8
                            throw cancellation
                        }
                    },
                )
                yield()
                now = 12
                val result = scope.attempt {
                    now = 20
                    42
                }
                yield()
                now = 23
                result
            }
        assertEquals(42, value)
        observation.finish(HostedDiagnosticOutcome.Completed)
        val row = receipts.single().readActions.single { it.mode == IntellijReadActionMode.READ }
        assertEquals(HostedReadActionOutcomes(1, 1, 0, 0, 0, HostedReadCallCountQualification.EXACT), row.submissions)
        assertEquals(HostedReadActionOutcomes(2, 1, 1, 0, 0, HostedReadCallCountQualification.EXACT), row.attempts)
        assertExactWait(row, HostedReadActionWait.INITIAL_ADMISSION, 1, 5, 5)
        assertExactWait(row, HostedReadActionWait.RETRY_ADMISSION, 1, 4, 4)
        assertExactWait(row, HostedReadActionWait.RETURN_DISPATCH, 1, 3, 3)
        assertEquals(1, entries.size)
        assertEquals(0L, entries.single().submittedNanos)
    }

    @Test
    fun `cancellation and failure before acquisition preserve original throwables`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val observation = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        val cancellation = ProcessCanceledException()
        assertSame(
            cancellation,
            assertThrows<ProcessCanceledException> {
                runTest {
                    observation.observeReadAction(IntellijReadActionKind.RELATION, IntellijReadActionMode.READ) {
                        yield()
                        throw cancellation
                    }
                }
            },
        )
        val failure = IllegalStateException("scripted platform failure")
        assertSame(
            failure,
            assertThrows<IllegalStateException> {
                runTest {
                    observation.observeReadAction(IntellijReadActionKind.RELATION, IntellijReadActionMode.READ) {
                        throw failure
                    }
                }
            },
        )
        observation.finish(HostedDiagnosticOutcome.Completed)
        val row = receipts.single().readActions.single { it.mode == IntellijReadActionMode.READ }
        assertEquals(HostedReadActionOutcomes(2, 0, 1, 1, 0, HostedReadCallCountQualification.EXACT), row.submissions)
        assertEquals(HostedReadActionOutcomes(0, 0, 0, 0, 0, HostedReadCallCountQualification.EXACT), row.attempts)
        assertEquals(1L, row.cancelledBeforeAcquisition)
        assertEquals(1L, row.failedBeforeAcquisition)
    }

    @Test
    fun `caller cancellation and subsequent native drainage are separate observed facts`() {
        var now = 0L
        val accounting = HostedReadActionAccounting({ now }, 100)
        val scope = accounting.submit(IntellijReadActionKind.RELATION, IntellijReadActionMode.READ)
        now = 2
        val attempt = scope.enterAttempt()
        now = 5
        scope.finish(IntellijReadCallOutcome.CANCELLED)
        now = 11
        attempt.finish(IntellijReadCallOutcome.CANCELLED)
        val row = accounting.finish().single { it.mode == IntellijReadActionMode.READ }
        assertEquals(HostedReadActionOutcomes(1, 0, 1, 0, 0, HostedReadCallCountQualification.EXACT), row.submissions)
        assertEquals(HostedReadActionOutcomes(1, 0, 1, 0, 0, HostedReadCallCountQualification.EXACT), row.attempts)
        assertExactWait(row, HostedReadActionWait.AFTER_COMPLETION_DRAINAGE, 1, 6, 6)
    }

    @Test
    fun `unfinished attempt remains unfinished when drainage arrives after publication`() {
        val accounting = HostedReadActionAccounting({ 0L }, 100)
        val scope = accounting.submit(IntellijReadActionKind.RELATION, IntellijReadActionMode.READ)
        val attempt = scope.enterAttempt()
        scope.finish(IntellijReadCallOutcome.CANCELLED)
        val row = accounting.finish().single { it.mode == IntellijReadActionMode.READ }
        attempt.finish(IntellijReadCallOutcome.CANCELLED)
        assertEquals(HostedReadActionOutcomes(1, 0, 0, 0, 1, HostedReadCallCountQualification.EXACT), row.attempts)
        assertEquals(0L, row.waits.single { it.wait == HostedReadActionWait.AFTER_COMPLETION_DRAINAGE }.samples)
        assertEquals(row, accounting.finish().single { it.mode == IntellijReadActionMode.READ })
    }

    @Test
    fun `acquisition after cancelled submission is exposed without rewriting cancellation evidence`() {
        var now = 0L
        val accounting = HostedReadActionAccounting({ now }, 100)
        val scope = accounting.submit(IntellijReadActionKind.RELATION, IntellijReadActionMode.READ)
        now = 3
        scope.finish(IntellijReadCallOutcome.CANCELLED)
        now = 7
        scope.enterAttempt().finish(IntellijReadCallOutcome.CANCELLED)
        val row = accounting.finish().single { it.mode == IntellijReadActionMode.READ }
        assertEquals(1L, row.cancelledBeforeAcquisition)
        assertEquals(1L, row.attemptsAfterCompletion)
        assertExactWait(row, HostedReadActionWait.AFTER_COMPLETION_ADMISSION, 1, 4, 4)
    }

    @Test
    fun `scope ownership rejects overlap and duplicate completion without charging extra attempts`() {
        val accounting = HostedReadActionAccounting({ 0L }, 100)
        val scope = accounting.submit(IntellijReadActionKind.RELATION, IntellijReadActionMode.READ)
        val attempt = scope.enterAttempt()
        assertThrows<IllegalStateException> { scope.enterAttempt() }
        attempt.finish(IntellijReadCallOutcome.RETURNED)
        assertThrows<IllegalStateException> { attempt.finish(IntellijReadCallOutcome.RETURNED) }
        scope.finish(IntellijReadCallOutcome.RETURNED)
        assertThrows<IllegalStateException> { scope.finish(IntellijReadCallOutcome.RETURNED) }
        assertEquals(
            HostedReadActionOutcomes(1, 1, 0, 0, 0, HostedReadCallCountQualification.EXACT),
            accounting.finish().single { it.mode == IntellijReadActionMode.READ }.attempts,
        )
    }

    @Test
    fun `encoded capability rows retain explicit zeros and closed wait vocabulary`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val observation = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        observation.finish(HostedDiagnosticOutcome.Completed)
        val encoded = Json.parseToJsonElement(receipts.single().encode()).jsonObject
        assertEquals("12", encoded.getValue("schemaVersion").jsonPrimitive.content)
        val rows = encoded.getValue("readActions").jsonArray
        assertEquals(listOf("READ"), rows.map { it.jsonObject.getValue("mode").jsonPrimitive.content })
        rows.forEach { value ->
            val row = value.jsonObject
            assertEquals(
                setOf(
                    "kind",
                    "mode",
                    "submissions",
                    "attempts",
                    "cancelledBeforeAcquisition",
                    "failedBeforeAcquisition",
                    "attemptsAfterCompletion",
                    "qualification",
                    "firstSubmission",
                    "waits",
                ),
                row.keys,
            )
            assertEquals("RELATION", row.getValue("kind").jsonPrimitive.content)
            assertEquals(
                "not-entered",
                row.getValue("firstSubmission").jsonObject.getValue("type").jsonPrimitive.content,
            )
            for (name in listOf("submissions", "attempts")) {
                val counts = row.getValue(name).jsonObject
                assertEquals(
                    setOf("entered", "returned", "cancelled", "failed", "unfinished", "qualification"),
                    counts.keys,
                )
                assertEquals("EXACT", counts.getValue("qualification").jsonPrimitive.content)
                counts
                    .filterKeys { it != "qualification" }
                    .values
                    .forEach { assertEquals("0", it.jsonPrimitive.content) }
            }
            assertEncodedWaits(row.getValue("waits").jsonArray)
        }
    }

    @Test
    fun `saturation of a nested outcome or wait rejects exact aggregate qualification`() {
        val counts = HostedReadActionAccounting({ 0L }, 2)
        repeat(3) {
            counts
                .submit(IntellijReadActionKind.RELATION, IntellijReadActionMode.READ)
                .finish(IntellijReadCallOutcome.RETURNED)
        }
        val count = counts.finish().single { it.mode == IntellijReadActionMode.READ }
        assertEquals(2L, count.submissions.entered)
        assertEquals(HostedReadCallCountQualification.SATURATED, count.qualification)
        var now = 0L
        val timings = HostedReadActionAccounting({ now }, 100)
        val first = timings.submit(IntellijReadActionKind.RELATION, IntellijReadActionMode.READ)
        now = Long.MAX_VALUE
        first.enterAttempt().finish(IntellijReadCallOutcome.RETURNED)
        first.finish(IntellijReadCallOutcome.RETURNED)
        now = 0
        val second = timings.submit(IntellijReadActionKind.RELATION, IntellijReadActionMode.READ)
        now = 1
        second.enterAttempt().finish(IntellijReadCallOutcome.RETURNED)
        second.finish(IntellijReadCallOutcome.RETURNED)
        val timing = timings.finish().single { it.mode == IntellijReadActionMode.READ }
        assertEquals(
            Long.MAX_VALUE,
            timing.waits.single { it.wait == HostedReadActionWait.INITIAL_ADMISSION }.durationNanos,
        )
        assertEquals(HostedReadCallCountQualification.SATURATED, timing.qualification)
    }

    private fun assertExactWait(
        row: HostedReadActionCount,
        wait: HostedReadActionWait,
        samples: Long,
        total: Long,
        maximum: Long,
    ) {
        assertEquals(
            HostedReadActionWaitCount(wait, samples, total, maximum, HostedReadCallCountQualification.EXACT),
            row.waits.single { it.wait == wait },
        )
    }

    private fun assertEncodedWaits(waits: JsonArray) {
        assertEquals(
            listOf(
                "INITIAL_ADMISSION",
                "RETRY_ADMISSION",
                "RETURN_DISPATCH",
                "AFTER_COMPLETION_ADMISSION",
                "AFTER_COMPLETION_DRAINAGE",
            ),
            waits.map {
                it.jsonObject.getValue("wait").jsonPrimitive.content
            },
        )
        waits.forEach {
            val wait = it.jsonObject
            assertEquals(setOf("wait", "samples", "durationNanos", "maximumNanos", "qualification"), wait.keys)
            assertEquals("EXACT", wait.getValue("qualification").jsonPrimitive.content)
            for (name in listOf("samples", "durationNanos", "maximumNanos")) {
                assertEquals("0", wait.getValue(name).jsonPrimitive.content)
            }
        }
    }
}
