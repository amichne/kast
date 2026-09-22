package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WorkspacePreparationDocumentTest {
    private val requestId = "00000000-0000-0000-0000-000000000001"
    private val json = Json { encodeDefaults = true }

    @Test
    fun `preparation document encodes each closed outcome with its discriminator`() {
        val cases =
            listOf(
                WorkspacePreparationState.Pending(IdeLifecycleStage.IMPORTING) to ("pending" to "stage"),
                WorkspacePreparationState.Completed(
                    IdeProjectTarget(
                        "00000000-0000-0000-0000-000000000002",
                        "00000000-0000-0000-0000-000000000003",
                        "/workspace",
                    )
                ) to ("completed" to "target"),
                WorkspacePreparationState.Rejected(WorkspacePreparationFailure.DEADLINE_EXCEEDED) to
                    ("rejected" to "failure"),
                WorkspacePreparationState.Blocked(IdeLifecycleFailure.TRUST_REQUIRED) to ("blocked" to "reason"),
            )
        for ((state, expected) in cases) {
            val document = WorkspacePreparationDocument(requestId, "/workspace", state)
            val encoded = json.encodeToJsonElement(WorkspacePreparationDocument.serializer(), document).jsonObject
            assertEquals(setOf("requestId", "root", "outcome"), encoded.keys)
            assertEquals(requestId, encoded.getValue("requestId").jsonPrimitive.content)
            assertEquals("/workspace", encoded.getValue("root").jsonPrimitive.content)
            val outcome = encoded.getValue("outcome").jsonObject
            assertEquals(setOf("type", expected.second), outcome.keys)
            assertEquals(expected.first, outcome.getValue("type").jsonPrimitive.content)
        }
    }

    @Test
    fun `activity encodes bounded failure evidence without source or workspace paths`() {
        val activity =
            WorkspacePreparationActivity(
                requestId,
                WorkspacePreparationActivityOutcome.Blocked(IdeLifecycleFailure.TRUST_REQUIRED),
            )
        val encoded = json.encodeToJsonElement(WorkspacePreparationActivity.serializer(), activity).jsonObject
        assertEquals(setOf("requestId", "outcome", "event"), encoded.keys)
        assertEquals("workspace-preparation", encoded.getValue("event").jsonPrimitive.content)
        val outcome = encoded.getValue("outcome").jsonObject
        assertEquals(setOf("type", "reason"), outcome.keys)
        assertEquals("blocked", outcome.getValue("type").jsonPrimitive.content)
        assertEquals("TRUST_REQUIRED", outcome.getValue("reason").jsonPrimitive.content)
        val incompatible =
            json
                .encodeToString(WorkspacePreparationActivity.serializer(), activity)
                .replace("TRUST_REQUIRED", "UNKNOWN")
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<WorkspacePreparationActivity>(incompatible)
        }
    }
}
