package io.github.amichne.kast.appserver.acceptance.hostedchange

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativeFixtureControlsTest {
    @Test
    fun `probe evidence preserves document state and direct PSI container`() {
        val evidence =
            NativeProbeEvidence(
                savedSha256 = "a".repeat(64),
                documentSha256 = "b".repeat(64),
                documentState = NativeDocumentState.DIRTY_COMMITTED,
                syntax = NativeSyntax.CLEAN,
                undo = NativeUndo.PRODUCTION_CHANGE,
                declarations = listOf(NativeDeclaration("added", "Nested", NativeDeclarationKind.FUNCTION)),
            )
        assertEquals(0, evidence.functionCount("added", "NativeChangeTarget"))
        assertEquals(1, evidence.functionCount("added", "Nested"))
        assertEquals(JsonPrimitive("DIRTY_COMMITTED"), evidence.summary()["documentState"])
    }

    @Test
    fun `rejected and uncertain probe results cannot become admitted source evidence`() {
        for (outcome in listOf("REJECTED", "EFFECT_UNCERTAIN")) {
            val response = buildJsonObject {
                put("command", "OBSERVE")
                put("outcome", outcome)
                put("failure", "DOCUMENT_IMAGE_CHANGED")
            }
            val failure =
                assertThrows(NativeRejected::class.java) {
                    NativeFixtureControls.admitProbeResponse(response, NativeProbeCommand.OBSERVE)
                }
            assertEquals(NativeFailure.PROBE_REJECTED, failure.failure)
        }
    }

    @Test
    fun `unknown response fields and protocol version fail closed before evidence decoding`() {
        val response = buildJsonObject {
            put("version", 2)
            put("id", "11111111-1111-1111-1111-111111111111")
            put("command", "OBSERVE")
            put("outcome", "COMPLETED")
            put("evidence", JsonObject(emptyMap()))
            put("source", "private-source")
        }
        val failure =
            assertThrows(NativeRejected::class.java) {
                NativeFixtureControls.admitProbeResponse(response, NativeProbeCommand.OBSERVE)
            }
        assertEquals(NativeFailure.CONTROL_REJECTED, failure.failure)
    }
}
