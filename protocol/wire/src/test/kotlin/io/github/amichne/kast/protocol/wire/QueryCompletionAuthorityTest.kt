package io.github.amichne.kast.protocol.wire

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class QueryCompletionAuthorityTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `query results cannot independently claim a cursor or terminal reason`() {
        for (value in listOf(
            LegacyQueryResult(continuation = "query:v1:00000000-0000-0000-0000-000000000001"),
            LegacyQueryResult(terminalReason = "upstream-incomplete"),
            LegacyQueryResult(continuation = "query:v1:00000000-0000-0000-0000-000000000001",
                terminalReason = "no-progress"),
        )) assertInstanceOf(WireDecoding.Rejected::class.java, CanonicalQuerySerializers.result.decode(
            json.encodeToJsonElement(LegacyQueryResult.serializer(), value), WireValueRole.RESULT,
        ))
    }

    @Test
    fun `qualified query evidence requires an explicit closed continuation state`() {
        assertInstanceOf(WireDecoding.Rejected::class.java, CanonicalQuerySerializers.qualification.decode(
            json.encodeToJsonElement(QualificationWithoutProgress.serializer(), QualificationWithoutProgress()),
            WireValueRole.QUALIFICATION,
        ))
    }
}

/** Deliberately incompatible pre-refinement shapes prove that outcome authority cannot be split across payloads. */
@Serializable
private data class LegacyQueryResult(
    val items: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val continuation: String? = null,
    val terminalReason: String? = null,
)

@Serializable
private data class QualificationWithoutProgress(
    val knownMinimum: Int = 0,
    val limitations: List<String> = listOf("work-limit-reached"),
)
