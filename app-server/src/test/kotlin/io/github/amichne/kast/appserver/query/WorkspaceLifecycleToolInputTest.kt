package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkspaceLifecycleToolInputTest {
    @Test
    fun `tool verbosity preserves canonical lifecycle identity`() {
        for (verbose in listOf(false, true)) {
            val input: WorkspaceLifecycleToolInput =
                WorkspaceLifecycleToolInput.Open("/workspace", "request-1", verbose)
            val encoded = Json {
                encodeDefaults = true
            }
                .encodeToJsonElement(WorkspaceLifecycleToolInput.serializer(), input)
            assertEquals(verbose.toString(), encoded.jsonObject.getValue("verbose").jsonPrimitive.content)
            val decoded = Json.decodeFromJsonElement(WorkspaceLifecycleToolInput.serializer(), encoded)
            assertEquals(WorkspaceLifecycleRequest.Open("/workspace", "request-1"), decoded.canonical())
            assertEquals(verbose, decoded.verbose)
        }
        val original =
            Json.encodeToJsonElement(WorkspaceLifecycleRequest.serializer(), WorkspaceLifecycleRequest.Inspect)
        val default = Json.decodeFromJsonElement(WorkspaceLifecycleToolInput.serializer(), original)
        assertEquals(false, default.verbose)
        assertEquals(WorkspaceLifecycleRequest.Inspect, default.canonical())
    }
}
