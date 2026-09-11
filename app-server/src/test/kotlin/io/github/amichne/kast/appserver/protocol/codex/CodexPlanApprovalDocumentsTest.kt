package io.github.amichne.kast.appserver.protocol.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CodexPlanApprovalDocumentsTest {
    @Test
    fun `file updates retain the required kind discriminator`() {
        val update = CodexPlanApprovalDocuments.FileUpdate("/tmp/preview.kt", "@@ -1 +1 @@\n-old\n+new\n")

        assertEquals(
            Json.parseToJsonElement(
                """{"path":"/tmp/preview.kt","diff":"@@ -1 +1 @@\n-old\n+new\n","kind":{"type":"update"}}"""
            ),
            CodexPlanApprovalDocuments.encode(update),
        )
    }

    @Test
    fun `every approval lifecycle qualification witness retains its update kind`() {
        val lifecycleSchemas =
            setOf(CodexOwnedSchema.ITEM_STARTED_NOTIFICATION, CodexOwnedSchema.ITEM_COMPLETED_NOTIFICATION)
        val lifecycle = CodexPlanApprovalProjection.qualificationWitnesses().filter { it.first in lifecycleSchemas }

        assertEquals(4, lifecycle.size)
        lifecycle.forEach { (_, document) ->
            val update = document.getValue("item").jsonObject.getValue("changes").jsonArray.single().jsonObject
            assertEquals(Json.parseToJsonElement("""{"type":"update"}"""), update.getValue("kind"))
        }
    }
}
