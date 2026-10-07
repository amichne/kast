package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryOriginalFailureContractTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `execution detail has finite concrete encoded shape`() {
        val original = QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.NON_ADVANCING_CONTINUATION)
        val encoded = json.encodeToJsonElement(QueryOriginalFailureDocument.serializer(), original).jsonObject
        assertEquals(setOf("type", "reason"), encoded.keys)
        assertEquals("EXECUTION", encoded.getValue("type").jsonPrimitive.content)
        assertEquals("NON_ADVANCING_CONTINUATION", encoded.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun `workspace detail preserves its concrete leaf shape`() {
        val encoded =
            json
                .encodeToJsonElement(
                    QueryOriginalFailureDocument.serializer(),
                    QueryRunRejection.WorkspaceNotReady,
                )
                .jsonObject
        assertEquals(setOf("type"), encoded.keys)
        assertEquals("WORKSPACE_NOT_READY", encoded.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `invocation stop rejects completion policy failure instead of accepting unreachable schema cases`() {
        val invalid =
            InvalidInvocationFailure(
                "INVALID_STATE",
                QueryRunRejection.CompletionUnsupported(
                    QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
                    QueryCompletionUnsupportedReason.UNSUPPORTED_OUTPUT,
                ),
            )
        val encoded = json.encodeToJsonElement(InvalidInvocationFailure.serializer(), invalid)
        assertThrows(SerializationException::class.java) {
            json.decodeFromJsonElement(QueryInvocationOutcome.serializer(), encoded)
        }
    }

    @Test
    fun `completion verdicts cannot be nested as original failures`() {
        for (type in listOf("COMPLETION_UNSUPPORTED", "COMPLETION_UNPROVEN", "UNKNOWN")) {
            val invalid = json.encodeToString(InvalidOriginalFailure.serializer(), InvalidOriginalFailure(type))
            assertThrows(SerializationException::class.java) {
                json.decodeFromString(QueryOriginalFailureDocument.serializer(), invalid)
            }
        }
    }
}

/** Deliberately invalid discriminator; concrete detail cannot recursively contain a completion verdict. */
@Serializable
private data class InvalidOriginalFailure(
    val type: String,
    val detail: QueryRunRejection.WorkspaceNotReady = QueryRunRejection.WorkspaceNotReady,
)

@Serializable private data class InvalidInvocationFailure(val type: String, val failure: QueryRunRejection)
