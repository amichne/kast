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
    fun `CLI rejection projection uses hyphens while internal wire spelling is not accepted`() {
        for ((reason, expected) in
            listOf(
                "exact-symbol-required" to NativeExpectedRejection.EXACT_SYMBOL_REQUIRED,
                "workspace-not-ready" to NativeExpectedRejection.WORKSPACE_NOT_READY,
                "content-changed" to NativeExpectedRejection.CONTENT_CHANGED,
                "exact_symbol_required" to NativeExpectedRejection.OTHER_REJECTION,
            )) {
            val result =
                NativeToolResult.Document(
                    buildJsonObject {
                        put("status", "rejected")
                        put("reason", reason)
                    },
                    NativeToolSuccess.SUCCEEDED,
                )
            assertEquals(expected, result.rejectionObservation())
        }
    }

    @Test
    fun `manual recovery qualification requires exact CLI status state and qualification`() {
        val payload = buildJsonObject {
            put("status", "qualified")
            put("state", "recovery-required")
            put("qualification", "manual-recovery-required")
        }
        assertEquals(
            NativeRecoveryObservation.MANUAL_RECOVERY_REQUIRED,
            NativeToolResult.Document(payload, NativeToolSuccess.SUCCEEDED).recoveryObservation(),
        )
        for (field in listOf("status", "state", "qualification")) {
            val changed = kotlinx.serialization.json.JsonObject(payload + (field to JsonPrimitive("unknown")))
            assertEquals(
                NativeRecoveryObservation.OTHER_RESULT,
                NativeToolResult.Document(changed, NativeToolSuccess.SUCCEEDED).recoveryObservation(),
            )
        }
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
