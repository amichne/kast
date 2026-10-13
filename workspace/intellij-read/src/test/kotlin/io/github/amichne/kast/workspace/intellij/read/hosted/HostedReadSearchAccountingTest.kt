package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadSearch
import io.github.amichne.kast.workspace.intellij.read.search
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HostedReadSearchAccountingTest {
    @Test
    fun `file index searches retain first callback and each no-callback terminal cause`() {
        listOf(IntellijReadSearch.SCOPED_FILES, IntellijReadSearch.EXACT_PACKAGE_FILES).forEach { search ->
            var now = 0L
            val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
            val observation = HostedReadDiagnostics({ now }, publish = receipts::add)
            observation.search(search) { scope ->
                now = 23L
                scope.callbackEntered()
                now = 90L
                scope.callbackEntered()
            }
            observation.search(search) {}
            assertThrows<ProcessCanceledException> { observation.search(search) { throw ProcessCanceledException() } }
            assertThrows<IllegalStateException> {
                observation.search(search) { throw IllegalStateException("scripted") }
            }
            observation.finish(HostedDiagnosticOutcome.Completed)
            assertEquals(
                HostedReadSearchCount(search, 4, 1, 1, 1, 1, 0, HostedReadCallCountQualification.EXACT, 23, 23),
                receipts.single().nativeSearches.single { it.search == search },
            )
            val index = receipts.single().nativeCalls.single { it.call == search.call && it.entered != 0L }
            assertEquals(4L, index.entered)
            assertEquals(2L, index.returned)
            assertEquals(1L, index.cancelled)
            assertEquals(1L, index.failed)
        }
    }

    @Test
    fun `each invocation contributes its own first callback delay exactly once`() {
        var now = 0L
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val observation = HostedReadDiagnostics({ now }, publish = receipts::add)
        observation.search(IntellijReadSearch.REFERENCES) { scope ->
            now = 3L
            repeat(20) { scope.callbackEntered() }
        }
        observation.search(IntellijReadSearch.REFERENCES) { scope ->
            now = 10L
            scope.callbackEntered()
            now = 100L
            scope.callbackEntered()
        }
        observation.finish(HostedDiagnosticOutcome.Completed)
        assertEquals(
            HostedReadSearchCount(
                IntellijReadSearch.REFERENCES,
                2,
                2,
                0,
                0,
                0,
                0,
                HostedReadCallCountQualification.EXACT,
                10,
                7,
            ),
            receipts.single().nativeSearches.single { it.search == IntellijReadSearch.REFERENCES },
        )
    }

    @Test
    fun `explicit search scope associates a callback on another thread`() {
        var now = 0L
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val observation = HostedReadDiagnostics({ now }, publish = receipts::add)
        observation.search(IntellijReadSearch.DEFINITIONS) { scope ->
            now = 11L
            val failure = AtomicReference<Throwable>()
            val callback = Thread {
                runCatching { scope.callbackEntered() }.exceptionOrNull()?.let(failure::set)
            }
            callback.start()
            callback.join()
            failure.get()?.let { throw it }
        }
        observation.finish(HostedDiagnosticOutcome.Completed)
        assertEquals(
            HostedReadSearchCount(
                IntellijReadSearch.DEFINITIONS,
                1,
                1,
                0,
                0,
                0,
                0,
                HostedReadCallCountQualification.EXACT,
                11,
                11,
            ),
            receipts.single().nativeSearches.single { it.search == IntellijReadSearch.DEFINITIONS },
        )
    }

    @Test
    fun `absence retains finite terminal causes and unfinished evidence survives late drainage`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val observation = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        observation.search(IntellijReadSearch.REFERENCES) {}
        val cancellation = ProcessCanceledException()
        assertSame(
            cancellation,
            assertThrows<ProcessCanceledException> {
                observation.search(IntellijReadSearch.REFERENCES) { throw cancellation }
            },
        )
        val failure = IllegalStateException("scripted failure")
        assertSame(
            failure,
            assertThrows<IllegalStateException> {
                observation.search(IntellijReadSearch.REFERENCES) { throw failure }
            },
        )
        val pending = observation.enterSearch(IntellijReadSearch.REFERENCES)
        observation.finish(HostedDiagnosticOutcome.Completed)
        pending.callbackEntered()
        pending.finish(IntellijReadCallOutcome.RETURNED)
        assertEquals(
            HostedReadSearchCount(
                IntellijReadSearch.REFERENCES,
                4,
                0,
                1,
                1,
                1,
                1,
                HostedReadCallCountQualification.EXACT,
                0,
                0,
            ),
            receipts.single().nativeSearches.single { it.search == IntellijReadSearch.REFERENCES },
        )
    }

    @Test
    fun `encoded discovery decisions declare explicit zero capability`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val observation = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        observation.finish(HostedDiagnosticOutcome.Completed)
        val encoded = Json.parseToJsonElement(receipts.single().encode()).jsonObject
        val discoveryDecisions =
            encoded.getValue("counters").jsonArray.filter {
                it.jsonObject.getValue("counter").jsonPrimitive.content in
                    setOf("DISCOVERY_SCOPE_FILES_ADMITTED", "DISCOVERY_SCOPE_FILES_EXCLUDED")
            }
        assertEquals(2, discoveryDecisions.size)
        discoveryDecisions.forEach {
            assertEquals("NONE", it.jsonObject.getValue("contributor").jsonPrimitive.content)
            assertEquals("0", it.jsonObject.getValue("count").jsonPrimitive.content)
        }
    }

    @Test
    fun `ownership work survives both completion and moved freshness rejection with explicit zero capability`() {
        val counters =
            setOf(
                IntellijReadCounter.NAMED_SOURCE_MEMBERSHIP_CHECKS,
                IntellijReadCounter.NAMED_SOURCE_ROOT_PATH_CONVERSIONS,
                IntellijReadCounter.NAMED_SOURCE_OWNERSHIP_PROBES,
                IntellijReadCounter.NAMED_SOURCE_EXCLUSION_PROBES,
                IntellijReadCounter.DISCOVERY_PATH_OWNERSHIP_PROBES,
                IntellijReadCounter.DISCOVERY_DIRECTORY_PATH_CONVERSIONS,
            )
        for (outcome in
            listOf(
                HostedDiagnosticOutcome.Completed,
                HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CONTENT_MOVED),
            )) {
            val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
            val observation = HostedReadDiagnostics({ 0L }, publish = receipts::add)
            observation.count(IntellijReadCounter.NAMED_SOURCE_MEMBERSHIP_CHECKS, amount = 2)
            observation.count(IntellijReadCounter.NAMED_SOURCE_OWNERSHIP_PROBES, amount = 5)
            observation.finish(outcome)
            val receipt = receipts.single()
            assertEquals(outcome, receipt.outcome)
            assertEquals(observation.identity.value, receipt.readId)
            val rows =
                Json.parseToJsonElement(receipt.encode()).jsonObject.getValue("counters").jsonArray.filter {
                    it.jsonObject.getValue("counter").jsonPrimitive.content in counters.map { counter -> counter.name }
                }
            assertEquals(counters.size, rows.size)
            rows.forEach { row ->
                val fields = row.jsonObject
                val name = fields.getValue("counter").jsonPrimitive.content
                val expected =
                    when (name) {
                        "NAMED_SOURCE_MEMBERSHIP_CHECKS" -> "2"
                        "NAMED_SOURCE_OWNERSHIP_PROBES" -> "5"
                        else -> "0"
                    }
                assertEquals(expected, fields.getValue("count").jsonPrimitive.content)
                assertEquals("NONE", fields.getValue("contributor").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `encoded search aggregates declare zero entries and exact fixed fields`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val observation = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        observation.finish(HostedDiagnosticOutcome.Completed)
        val encoded = Json.parseToJsonElement(receipts.single().encode()).jsonObject
        assertEquals("15", encoded.getValue("schemaVersion").jsonPrimitive.content)
        val searches = encoded.getValue("nativeSearches").jsonArray
        assertEquals(
            listOf("REFERENCES", "DEFINITIONS", "SCOPED_FILES", "EXACT_PACKAGE_FILES"),
            searches.map {
                it.jsonObject.getValue("search").jsonPrimitive.content
            },
        )
        searches.forEach { row ->
            val actual = row.jsonObject
            assertEquals(
                setOf(
                    "search",
                    "entered",
                    "firstCallbackObserved",
                    "returnedWithoutCallback",
                    "cancelledWithoutCallback",
                    "failedWithoutCallback",
                    "unfinishedWithoutCallback",
                    "qualification",
                    "firstCallbackDelayTotalNanos",
                    "firstCallbackDelayMaximumNanos",
                    "workVisibility",
                ),
                actual.keys,
            )
            assertEquals("EXACT", actual.getValue("qualification").jsonPrimitive.content)
            assertEquals("NATIVE_INTERNAL_WORK_UNOBSERVED", actual.getValue("workVisibility").jsonPrimitive.content)
            actual
                .filterKeys { it != "search" && it != "qualification" && it != "workVisibility" }
                .values
                .forEach {
                    assertEquals("0", it.jsonPrimitive.content)
                }
        }
    }

    @Test
    fun `count and duration overflow are explicitly saturated`() {
        val counts = HostedReadSearchAccounting({ 0L }, 2, { IntellijReadCallScope.None })
        repeat(3) { counts.enter(IntellijReadSearch.REFERENCES).finish(IntellijReadCallOutcome.RETURNED) }
        val count = counts.finish().first()
        assertEquals(2L, count.entered)
        assertEquals(2L, count.returnedWithoutCallback)
        assertEquals(HostedReadCallCountQualification.SATURATED, count.qualification)

        var now = 0L
        val durations = HostedReadSearchAccounting({ now }, 100, { IntellijReadCallScope.None })
        val first = durations.enter(IntellijReadSearch.REFERENCES)
        now = Long.MAX_VALUE
        first.callbackEntered()
        first.finish(IntellijReadCallOutcome.RETURNED)
        now = 0
        val second = durations.enter(IntellijReadSearch.REFERENCES)
        now = 1
        second.callbackEntered()
        second.finish(IntellijReadCallOutcome.RETURNED)
        val duration = durations.finish().first()
        assertEquals(2L, duration.firstCallbackObserved)
        assertEquals(Long.MAX_VALUE, duration.firstCallbackDelayTotalNanos)
        assertEquals(HostedReadCallCountQualification.SATURATED, duration.qualification)
    }
}
