package io.github.amichne.kast.appserver.protocol.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeToolIdentityTest {
    @Test
    fun `started call retains its native identity and arguments`() {
        val item =
            Json.parseToJsonElement(
                    """{"type":"dynamicToolCall","id":"call-1","namespace":"kast","tool":"query","arguments":{"from":"example"},"status":"inProgress"}"""
                )
                .jsonObject
        assertEquals(item, (CodexToolCallProjector.projectStarted(item) as CodexToolCallProjection.Projected).item)
    }

    @Test
    fun `completed call retains the complete structured result`() {
        val item =
            Json.parseToJsonElement(
                    """{"type":"dynamicToolCall","id":"call-1","namespace":"kast","tool":"query","arguments":{},"status":"completed","success":true,"contentItems":[{"type":"inputText","text":"{\"status\":\"qualified\",\"items\":[],\"failures\":[\"budget\"]}"}]}"""
                )
                .jsonObject
        assertEquals(item, (CodexToolCallProjector.projectCompleted(item) as CodexToolCallProjection.Projected).item)
    }
}
