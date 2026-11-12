package io.github.amichne.kast.runtime.hosted.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PrivateGradleJvmObservationTest {
    @Test
    fun `selection evidence records authority without an installation path`() {
        for (authority in PrivateGradleJvmAuthority.entries) {
            val encoded =
                Json.parseToJsonElement(
                        Json.encodeToString(
                            PrivateGradleJvmObservation.serializer(),
                            PrivateGradleJvmObservation.Selected(authority),
                        )
                    )
                    .jsonObject
            assertEquals(setOf("type", "authority"), encoded.keys)
            assertEquals("selected", encoded.getValue("type").jsonPrimitive.content)
            assertEquals(authority.name, encoded.getValue("authority").jsonPrimitive.content)
        }
    }

    @Test
    fun `each rejection retains its finite reason`() {
        for (reason in PrivateGradleJvmFailure.entries) {
            val encoded =
                Json.parseToJsonElement(
                        Json.encodeToString(
                            PrivateGradleJvmObservation.serializer(),
                            PrivateGradleJvmObservation.Rejected(reason),
                        )
                    )
                    .jsonObject
            assertEquals(setOf("type", "reason"), encoded.keys)
            assertEquals("rejected", encoded.getValue("type").jsonPrimitive.content)
            assertEquals(reason.name, encoded.getValue("reason").jsonPrimitive.content)
        }
    }

    @Test
    fun `native daemon criteria delegation is not reported as a selected installation`() {
        val encoded =
            Json.parseToJsonElement(
                    Json.encodeToString(
                        PrivateGradleJvmObservation.serializer(),
                        PrivateGradleJvmObservation.DaemonCriteria,
                    )
                )
                .jsonObject
        assertEquals(setOf("type"), encoded.keys)
        assertEquals("daemon_criteria", encoded.getValue("type").jsonPrimitive.content)
    }
}
