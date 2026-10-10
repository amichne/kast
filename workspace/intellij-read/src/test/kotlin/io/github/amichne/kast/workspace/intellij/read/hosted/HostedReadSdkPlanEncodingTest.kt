package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.call
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedReadSdkPlanEncodingTest {
    @Test
    fun `SDK plan receipt distinguishes required future reads from actual bytes and a finite rejection`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val observation = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        observation.call(IntellijReadCall.SDK_FILE_PLAN) {
            observation.count(IntellijReadCounter.DEPENDENCY_SDK_PLANNED_FILES, amount = 12)
            observation.count(IntellijReadCounter.DEPENDENCY_SDK_MINIMUM_HASH_READS, amount = 12)
            observation.terminated(IntellijReadTermination.SEMANTIC_INPUT_MINIMUM_HASH_WORK_UNAVAILABLE)
        }
        observation.finish(HostedDiagnosticOutcome.Completed)
        val document = Json.parseToJsonElement(receipts.single().encode()).jsonObject
        assertEquals("12", document.getValue("schemaVersion").jsonPrimitive.content)
        val counters = document.getValue("counters").jsonArray.map { it.jsonObject }
        for ((counter, expected) in
            listOf(
                "DEPENDENCY_SDK_PLANNED_FILES" to "12",
                "DEPENDENCY_SDK_MINIMUM_HASH_READS" to "12",
                "DEPENDENCY_HASH_BYTES_READ" to "0",
                "DEPENDENCY_SDK_FILE_PLANS_COMPLETED" to "0",
            )) {
            assertEquals(
                expected,
                counters
                    .single { it.getValue("counter").jsonPrimitive.content == counter }
                    .getValue("count")
                    .jsonPrimitive
                    .content,
            )
        }
        val call =
            document
                .getValue("nativeCalls")
                .jsonArray
                .map { it.jsonObject }
                .single { it.getValue("call").jsonPrimitive.content == "SDK_FILE_PLAN" }
        assertEquals("1", call.getValue("returned").jsonPrimitive.content)
        assertEquals("0", call.getValue("unfinished").jsonPrimitive.content)
        val failure = document.getValue("terminations").jsonArray.single().jsonObject
        assertEquals("SEMANTIC_INPUT_MINIMUM_HASH_WORK_UNAVAILABLE", failure.getValue("reason").jsonPrimitive.content)
        assertEquals(setOf("reason", "contributor"), failure.keys)
        assertEquals("NONE", failure.getValue("contributor").jsonPrimitive.content)
    }
}
