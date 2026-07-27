package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.RuntimeOpenProjectRequest
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRequestId
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRoot
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RuntimeOpenProjectContractTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `request retains canonical path and UUID domain values on the wire`() {
        val root = RuntimeOpenProjectRoot.of(tempDir)
        val requestId = RuntimeOpenProjectRequestId.parse(
            "a7370b30-7ca5-4fa5-93c0-e59d30aa6157",
        )
        val request = RuntimeOpenProjectRequest(root, requestId)

        assertEquals(
            request,
            Json.decodeFromString<RuntimeOpenProjectRequest>(
                Json.encodeToString(request),
            ),
        )
    }

    @Test
    fun `wire parsing rejects noncanonical roots and malformed request IDs`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeOpenProjectRoot.parse(tempDir.resolve(".").toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeOpenProjectRequestId.parse("not-a-uuid")
        }
    }
}
