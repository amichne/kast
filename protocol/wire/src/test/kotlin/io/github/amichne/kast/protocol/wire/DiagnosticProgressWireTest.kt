package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticInventoryDocument
import io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument
import io.github.amichne.kast.protocol.contract.DiagnosticProgressDocument
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStage
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStop
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DiagnosticProgressWireTest {
    @Test
    fun `enumeration progress serializes explicit unknown inventory and exact stop`() {
        val value = enumerationResult()
        val encoded =
            CanonicalReadSerializers.diagnosticCheckResult.encode(value, WireValueRole.RESULT)
                as WireValueEncoding.Encoded
        val document = encoded.value.jsonObject.getValue("progress").jsonObject
        assertEquals("enumeration", document.getValue("stage").jsonPrimitive.content)
        assertEquals("enumeration_file_limit", document.getValue("stop").jsonPrimitive.content)
        assertEquals("0", document.getValue("knownDiagnosticCount").jsonPrimitive.content)
        val inventory = document.getValue("inventory").jsonObject
        assertEquals(setOf("type"), inventory.keys)
        assertEquals("enumerating", inventory.getValue("type").jsonPrimitive.content)
        assertFalse(document.containsKey("executionBudget"))
    }

    @Test
    fun `qualified empty diagnostic page preserves its continuation`() {
        val value =
            DiagnosticCheckQualification.create(
                    DiagnosticKnownCountDocument.parse(0).refined(),
                    false,
                    emptyList(),
                    emptyList(),
                    ProtocolText.parse("diagnostic:v1:fixture").refined(),
                )
                .refined()
        val encoded =
            CanonicalReadSerializers.diagnosticCheckQualification.encode(value, WireValueRole.QUALIFICATION)
                as WireValueEncoding.Encoded
        assertEquals("diagnostic:v1:fixture", encoded.value.jsonObject.getValue("continuation").jsonPrimitive.content)
        assertEquals(
            WireDecoding.Decoded(value),
            CanonicalReadSerializers.diagnosticCheckQualification.decode(encoded.value, WireValueRole.QUALIFICATION),
        )
    }

    @Test
    fun `every diagnostic rejection has its own exact finite wire name`() {
        for (reason in DiagnosticCheckRejection.entries) {
            val encoded =
                CanonicalReadSerializers.diagnosticCheckRejection.encode(reason, WireValueRole.REJECTION)
                    as WireValueEncoding.Encoded
            assertEquals(reason.name.lowercase(), encoded.value.jsonPrimitive.content)
            assertEquals(
                WireDecoding.Decoded(reason),
                CanonicalReadSerializers.diagnosticCheckRejection.decode(encoded.value, WireValueRole.REJECTION),
            )
        }
    }

    @Test
    fun `unknown progress stage rejects at wire boundary`() {
        val valid =
            CanonicalReadSerializers.diagnosticCheckResult.encode(enumerationResult(), WireValueRole.RESULT)
                as WireValueEncoding.Encoded
        val malformed = Json.parseToJsonElement(valid.value.toString().replaceFirst("\"enumeration\"", "\"invented\""))
        val decoded = CanonicalReadSerializers.diagnosticCheckResult.decode(malformed, WireValueRole.RESULT)
        assertEquals(WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.RESULT)), decoded)
    }

    private fun enumerationResult(): DiagnosticCheckResult {
        val progress =
            DiagnosticProgressDocument(
                DiagnosticProgressStage.ENUMERATION,
                DiagnosticInventoryDocument.Enumerating,
                emptyList(),
                stop = DiagnosticProgressStop.ENUMERATION_FILE_LIMIT,
                knownDiagnosticCount = DiagnosticKnownCountDocument.parse(0).refined(),
            )
        return DiagnosticCheckResult(
            BoundedProtocolList.create(emptyList<io.github.amichne.kast.protocol.contract.DiagnosticDocument>())
                .refined(),
            progress,
        )
    }

    private fun <T> Refinement<T, *>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid fixture: $failure")
        }
}
