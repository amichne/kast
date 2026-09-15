package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SymbolRevalidationTargetTest {
    @Test
    fun `explicit exact revalidation is a distinct inspect target`() {
        val encoded = """{"target":{"type":"revalidate_exact","selector":"exact:v5:0123456789abcdef012345"}}"""
        val request = Json.decodeFromString<SymbolInspectRequest>(encoded)
        assertEquals(encoded, Json.encodeToString(request))
    }
}
