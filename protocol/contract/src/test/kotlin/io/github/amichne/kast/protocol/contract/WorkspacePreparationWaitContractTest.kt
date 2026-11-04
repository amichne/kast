package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WorkspacePreparationWaitContractTest {
    @Test
    fun `explicit indefinite preparation travels through the lifecycle request`() {
        val json = Json { encodeDefaults = true }
        val request = json.decodeFromString<WorkspaceLifecycleRequest>(json.encodeToString(IndefiniteOpen()))
        assertNull((request as WorkspaceLifecycleRequest.Open).preparationMillis)
    }
}

@Serializable
private data class IndefiniteOpen(
    @SerialName("type") val type: String = "open",
    val root: String = "/workspace",
    val requestId: String = "request",
    val preparationMillis: Long? = null,
)
