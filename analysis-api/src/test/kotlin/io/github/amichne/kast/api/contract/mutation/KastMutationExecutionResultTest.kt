package io.github.amichne.kast.api.contract.mutation

import io.github.amichne.kast.api.protocol.ApiErrorResponse
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KastMutationExecutionResultTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `terminal success round trips with deduplication evidence`() {
        val result = KastMutationExecutionResult.Failed(
            failure = KastMutationFailure.Thrown(
                ApiErrorResponse(
                    requestId = "test-request",
                    code = "TEST_FAILURE",
                    message = "expected",
                    retryable = false,
                ),
            ),
            deduplicated = true,
        )

        val encoded = json.encodeToString(KastMutationExecutionResult.serializer(), result)

        assertTrue(encoded.contains("\"type\":\"FAILED\""))
        assertEquals(result, json.decodeFromString(KastMutationExecutionResult.serializer(), encoded))
    }
}
