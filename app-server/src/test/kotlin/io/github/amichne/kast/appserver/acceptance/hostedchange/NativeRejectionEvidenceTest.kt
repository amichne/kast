package io.github.amichne.kast.appserver.acceptance.hostedchange

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeRejectionEvidenceTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `broker schema rejection retains its stronger boundary`() {
        assertEquals(
            NativeExpectedRejection.BROKER_INVALID_ARGUMENTS,
            NativeToolResult.BrokerRejected(NativeBrokerRejection.INVALID_ARGUMENTS).rejectionObservation(),
        )
        assertEquals(NativeExpectedRejection.RESPONSE_LOST, NativeToolResult.ResponseLost.rejectionObservation())
    }

    @Test
    fun `failed native expectation retains case and finite observed condition without payload`() {
        val report = directory.resolve("report.json")
        val evidence = NativeChangeEvidence(report)
        val result =
            NativeToolResult.Document(
                buildJsonObject {
                    put("status", "rejected")
                    put("reason", "private-source-and-path")
                },
                NativeToolSuccess.FAILED,
            )
        assertThrows(NativeRejected::class.java) {
            evidence.expectRejection("unsupported-intents", NativeExpectedRejection.BROKER_INVALID_ARGUMENTS, result)
        }
        val raw = Files.readString(report)
        val observed =
            (Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject)
                .objectAt("cases")
                .objectAt("unsupported-intents")
        assertEquals(JsonPrimitive("rejected"), observed["outcome"])
        assertEquals(JsonPrimitive("OTHER_REJECTION"), observed.objectAt("evidence")["observedRejection"])
        assertFalse(raw.contains("private-source-and-path"))
    }
}
