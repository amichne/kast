package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SymbolRevalidationTargetTest {
    @Test
    fun `explicit exact revalidation is a distinct inspect target`() {
        val encoded =
            Json.encodeToString(ExpectedRequest(ExpectedTarget("revalidate_exact", "exact:v5:0123456789abcdef012345")))
        val request = Json.decodeFromString<SymbolInspectRequest>(encoded)
        assertEquals(encoded, Json.encodeToString(request))
    }

    @Test
    fun `unknown inspection target rejects without falling back to strict or candidate`() {
        val encoded = Json.encodeToString(ExpectedRequest(ExpectedTarget("unknown", "exact:v5:0123456789abcdef012345")))
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            Json.decodeFromString<SymbolInspectRequest>(encoded)
        }
    }

    @Serializable private data class ExpectedRequest(val target: ExpectedTarget)

    @Serializable private data class ExpectedTarget(val type: String, val selector: String)
}
