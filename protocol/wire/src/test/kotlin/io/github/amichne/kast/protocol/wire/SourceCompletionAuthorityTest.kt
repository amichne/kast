package io.github.amichne.kast.protocol.wire

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class SourceCompletionAuthorityTest {
    @Test
    fun `source qualification requires explicit completion evidence beyond cursor availability`() {
        val json = Json { encodeDefaults = true }
        assertInstanceOf(
            WireDecoding.Rejected::class.java,
            CanonicalSourceReadSerializers.qualification.decode(
                json.encodeToJsonElement(LegacySourceQualification.serializer(), LegacySourceQualification()),
                WireValueRole.QUALIFICATION,
            ),
        )
    }
}

/** Deliberately incompatible old shape lacks a closed progress authority. */
@Serializable
private data class LegacySourceQualification(
    val knownMinimumEntityCount: Int = 0,
    val limitations: List<String> = listOf("work-limit-reached"),
    val continuation: LegacyUnavailable = LegacyUnavailable(),
)

@Serializable private data class LegacyUnavailable(val type: String = "unavailable")
