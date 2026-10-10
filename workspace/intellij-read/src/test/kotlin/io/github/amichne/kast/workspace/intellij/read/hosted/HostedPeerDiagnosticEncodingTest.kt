package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedPeerDiagnosticEncodingTest {
    @Test
    fun `peer counters encode explicit zeros without entering the native phase`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        HostedReadDiagnostics({ 0 }, publish = receipts::add).finish(HostedDiagnosticOutcome.Completed)
        val encoded = Json.parseToJsonElement(receipts.single().encode()).jsonObject
        assertEquals("11", encoded.getValue("schemaVersion").jsonPrimitive.content)
        for (counter in peerCounters) assertEquals("0", counterCount(encoded, counter))
        assertEquals("not-entered", encoded.getValue("nativePhase").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `peer success and finite rejection preserve phase and encoded outcome counts`() {
        for ((counter, outcome) in
            listOf(
                IntellijReadCounter.PEER_SITE_READS_COMPLETED to HostedDiagnosticOutcome.Completed,
                IntellijReadCounter.PEER_SITE_READS_REJECTED to
                    HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CONTENT_MOVED),
            )) {
            val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
            val diagnostic = HostedReadDiagnostics({ 0 }, publish = receipts::add)
            diagnostic.phase(IntellijReadPhase.PEER_SITE_ADMISSION)
            diagnostic.count(IntellijReadCounter.PEER_SITE_READS_STARTED)
            diagnostic.count(counter)
            diagnostic.finish(outcome)
            val encoded = Json.parseToJsonElement(receipts.single().encode()).jsonObject
            assertEquals("1", counterCount(encoded, IntellijReadCounter.PEER_SITE_READS_STARTED))
            assertEquals("1", counterCount(encoded, counter))
            assertEquals(
                "PEER_SITE_ADMISSION",
                encoded.getValue("nativePhase").jsonObject.getValue("phase").jsonPrimitive.content,
            )
            assertEquals(outcome, receipts.single().outcome)
        }
    }

    private fun counterCount(encoded: kotlinx.serialization.json.JsonObject, counter: IntellijReadCounter): String =
        encoded
            .getValue("counters")
            .jsonArray
            .single {
                it.jsonObject.getValue("counter").jsonPrimitive.content == counter.name
            }
            .jsonObject
            .getValue("count")
            .jsonPrimitive
            .content

    private val peerCounters =
        listOf(
            IntellijReadCounter.PEER_SITE_READS_STARTED,
            IntellijReadCounter.PEER_SITE_READS_COMPLETED,
            IntellijReadCounter.PEER_SITE_READS_REJECTED,
        )
}
