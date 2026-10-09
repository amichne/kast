package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.call
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HostedReadCallAccountingTest {
    @Test
    fun `raw counts and parentage do not depend on instrumentation timestamps`() {
        val counts =
            listOf(1L, 1000L).map { increment ->
                var now = 0L
                val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
                val entries = mutableListOf<HostedReadCallStarted>()
                val observation = HostedReadDiagnostics({ now }, publishCall = entries::add, publish = receipts::add)
                observation.call(IntellijReadCall.REFERENCE_SEARCH) {
                    repeat(3) {
                        observation.call(IntellijReadCall.REFERENCE_CALLBACK) { now += increment }
                    }
                }
                observation.finish(HostedDiagnosticOutcome.Completed)
                val receipt = receipts.single()
                val callback =
                    receipt.nativeCalls.single {
                        it.call == IntellijReadCall.REFERENCE_CALLBACK && it.parent is HostedReadCallParent.Call
                    }
                assertEquals(HostedReadCallParent.Call(IntellijReadCall.REFERENCE_SEARCH), callback.parent)
                assertEquals(3L, callback.entered)
                assertEquals(3L, callback.returned)
                assertEquals(0L, callback.unfinished)
                assertEquals(3 * increment, callback.durationNanos)
                assertEquals(2, entries.size, "Only first entry per boundary and parent is exported")
                assertEquals(IntellijReadCall.entries.size + 1, receipt.nativeCalls.size)
                receipt.nativeCalls.forEach {
                    assertEquals(it.entered, it.returned + it.cancelled + it.failed + it.unfinished)
                    assertEquals(HostedReadCallCountQualification.EXACT, it.qualification)
                }
                receipt.nativeCalls.map { listOf(it.entered, it.returned, it.cancelled, it.failed, it.unfinished) }
            }
        assertEquals(counts.first(), counts.last())
    }

    @Test
    fun `failure cancellation and unfinished native work retain distinct terminal counts`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val observation = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        val failure = IllegalStateException("owned fixture failure")
        assertSame(
            failure,
            assertThrows<IllegalStateException> {
                observation.call(IntellijReadCall.FILE_STREAM_READ) { throw failure }
            },
        )
        assertThrows<CancellationException> {
            observation.call(IntellijReadCall.FILE_STREAM_READ) { throw CancellationException() }
        }
        observation.call(IntellijReadCall.FILE_STREAM_READ) { 0 }
        val pending = observation.enterCall(IntellijReadCall.REFERENCE_SEARCH)
        observation.finish(HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CANCELLED))
        pending.finish(IntellijReadCallOutcome.CANCELLED)
        val receipt = receipts.single()
        val reads = receipt.nativeCalls.single { it.call == IntellijReadCall.FILE_STREAM_READ }
        assertEquals(
            listOf(3L, 1L, 1L, 1L, 0L),
            listOf(reads.entered, reads.returned, reads.cancelled, reads.failed, reads.unfinished),
        )
        val pendingSearch = receipt.nativeCalls.single { it.call == IntellijReadCall.REFERENCE_SEARCH }
        assertEquals(1L, pendingSearch.unfinished)
        assertEquals(0L, pendingSearch.cancelled)
    }

    @Test
    fun `call receipt encoding preserves discriminators counts and qualification`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val observation = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        observation.call(IntellijReadCall.FILE_STREAM_READ) { 0 }
        observation.finish(HostedDiagnosticOutcome.Completed)
        val receipt = receipts.single()
        val encoded = Json.parseToJsonElement(receipt.encode()).jsonObject
        assertEquals("9", encoded.getValue("schemaVersion").jsonPrimitive.content)
        val actual =
            encoded
                .getValue("nativeCalls")
                .jsonArray
                .first { it.jsonObject.getValue("call").jsonPrimitive.content == "FILE_STREAM_READ" }
                .jsonObject
        assertEquals(
            setOf(
                "call",
                "parent",
                "entered",
                "returned",
                "cancelled",
                "failed",
                "unfinished",
                "qualification",
                "firstEntry",
                "durationNanos",
            ),
            actual.keys,
        )
        assertEquals("EXACT", actual.getValue("qualification").jsonPrimitive.content)
        assertEquals("root", actual.getValue("parent").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("0", actual.getValue("unfinished").jsonPrimitive.content)
    }

    @Test
    fun `saturation is explicit and zero entries prove only declared boundary capability`() {
        val accounting = HostedReadCallAccounting({ 0L }, 2L)
        repeat(3) { accounting.enter(IntellijReadCall.FILE_STREAM_READ).finish(IntellijReadCallOutcome.RETURNED) }
        val counts = accounting.finish()
        val reads = counts.single { it.call == IntellijReadCall.FILE_STREAM_READ }
        assertEquals(2L, reads.entered)
        assertEquals(HostedReadCallCountQualification.SATURATED, reads.qualification)
        val unused = counts.single { it.call == IntellijReadCall.DEFINITION_SEARCH }
        assertEquals(0L, unused.entered)
        assertEquals(HostedReadCallEntry.NotEntered, unused.firstEntry)
        assertTrue(unused.qualification == HostedReadCallCountQualification.EXACT)
    }
}
