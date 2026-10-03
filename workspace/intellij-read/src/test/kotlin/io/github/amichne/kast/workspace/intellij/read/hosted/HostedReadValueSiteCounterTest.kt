package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedReadValueSiteCounterTest {
    @Test
    fun `provider-free receipt exposes both requested site admission counter alternatives as zero`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostic = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        diagnostic.finish(HostedDiagnosticOutcome.Completed)
        val expected =
            listOf(
                HostedNativeCount(IntellijReadCounter.VALUE_MODEL_SITE_REVALIDATIONS, IntellijReadContributor.NONE, 0),
                HostedNativeCount(
                    IntellijReadCounter.VALUE_MODEL_SITE_REVALIDATIONS_REJECTED,
                    IntellijReadContributor.NONE,
                    0,
                ),
            )
        assertEquals(
            expected,
            receipts.single().counters.filter { it.counter in expected.map { value -> value.counter } },
        )
        val encoded = Json.parseToJsonElement(receipts.single().encode()).jsonObject.getValue("counters").jsonArray
        for (value in expected) {
            val counter =
                encoded
                    .single { it.jsonObject.getValue("counter").jsonPrimitive.content == value.counter.name }
                    .jsonObject
            assertEquals(setOf("counter", "contributor", "count"), counter.keys)
            assertEquals("NONE", counter.getValue("contributor").jsonPrimitive.content)
            assertEquals(value.count, counter.getValue("count").jsonPrimitive.long)
        }
    }

    @Test
    fun `site admission rejection count remains finite and survives rejected receipt publication`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostic = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        diagnostic.count(IntellijReadCounter.VALUE_MODEL_SITE_REVALIDATIONS, amount = 3)
        diagnostic.count(IntellijReadCounter.VALUE_MODEL_SITE_REVALIDATIONS_REJECTED, amount = 2)
        diagnostic.finish(HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CANCELLED))
        val expected =
            listOf(
                HostedNativeCount(IntellijReadCounter.VALUE_MODEL_SITE_REVALIDATIONS, IntellijReadContributor.NONE, 3),
                HostedNativeCount(
                    IntellijReadCounter.VALUE_MODEL_SITE_REVALIDATIONS_REJECTED,
                    IntellijReadContributor.NONE,
                    2,
                ),
            )
        assertEquals(
            expected,
            receipts.single().counters.filter { it.counter in expected.map { value -> value.counter } },
        )
        val encoded = Json.parseToJsonElement(receipts.single().encode()).jsonObject.getValue("counters").jsonArray
        for (value in expected) {
            val counter =
                encoded
                    .single { it.jsonObject.getValue("counter").jsonPrimitive.content == value.counter.name }
                    .jsonObject
            assertEquals(setOf("counter", "contributor", "count"), counter.keys)
            assertEquals("NONE", counter.getValue("contributor").jsonPrimitive.content)
            assertEquals(value.count, counter.getValue("count").jsonPrimitive.long)
        }
    }
}
