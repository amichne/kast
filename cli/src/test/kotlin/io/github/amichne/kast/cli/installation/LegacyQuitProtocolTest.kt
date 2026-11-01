package io.github.amichne.kast.cli.installation

import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyQuitProtocolTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `normal quit needs the successful helper acknowledgement`() {
        assertEquals(
            LegacyQuitOutcome.REQUESTED,
            classifyLegacyQuit(LegacyHelperRead.Read("requested\n".encodeToByteArray())),
        )
        assertEquals(
            LegacyQuitOutcome.REJECTED,
            classifyLegacyQuit(LegacyHelperRead.Read("rejected\n".encodeToByteArray())),
        )
        assertEquals(
            LegacyQuitOutcome.HELPER_RESPONSE_INVALID,
            classifyLegacyQuit(LegacyHelperRead.Read("unknown".encodeToByteArray())),
        )
        assertEquals(
            LegacyQuitOutcome.HELPER_RESPONSE_INVALID,
            classifyLegacyQuit(LegacyHelperRead.Read(byteArrayOf(-1))),
        )
    }

    @Test
    fun `a nonzero helper cannot acknowledge even when it prints requested`() {
        val result = boundedLegacyHelper(listOf("/bin/sh", "-c", "printf requested; exit 7"), 256)
        assertEquals(LegacyHelperRead.Exited(7), result)
        assertEquals(LegacyQuitOutcome.HELPER_EXIT_FAILED, classifyLegacyQuit(result))
    }

    @Test
    fun `helper launch output capture timeout and interruption failures stay distinct`() {
        assertEquals(
            LegacyHelperRead.Unavailable(LegacyHelperFailure.START_FAILED),
            boundedLegacyHelper(listOf(temporary.resolve("missing-helper").toString()), 256),
        )
        assertEquals(
            LegacyHelperRead.Unavailable(LegacyHelperFailure.OUTPUT_LIMIT_EXCEEDED),
            boundedLegacyHelper(listOf("/bin/sh", "-c", "printf requested"), 2),
        )
        val cases =
            listOf(
                LegacyHelperFailure.START_FAILED to LegacyQuitOutcome.HELPER_START_FAILED,
                LegacyHelperFailure.TIMED_OUT to LegacyQuitOutcome.HELPER_TIMED_OUT,
                LegacyHelperFailure.CAPTURE_FAILED to LegacyQuitOutcome.HELPER_CAPTURE_FAILED,
                LegacyHelperFailure.OUTPUT_LIMIT_EXCEEDED to LegacyQuitOutcome.HELPER_OUTPUT_LIMIT_EXCEEDED,
                LegacyHelperFailure.INTERRUPTED to LegacyQuitOutcome.HELPER_INTERRUPTED,
            )
        for ((failure, outcome) in cases) assertEquals(
            outcome,
            classifyLegacyQuit(LegacyHelperRead.Unavailable(failure)),
        )
    }

    @Test
    fun `quit diagnostics encode bounded stage and finite outcome without helper payloads`() {
        val requested = encodeLegacyQuit(LegacyQuitObservation.Result(LegacyQuitOutcome.REQUESTED))
        val result = Json.parseToJsonElement(requested).jsonObject
        assertEquals(setOf("type", "stage", "outcome"), result.keys)
        assertEquals("RESULT", result.getValue("type").jsonPrimitive.content)
        assertEquals("NORMAL_QUIT", result.getValue("stage").jsonPrimitive.content)
        assertEquals("REQUESTED", result.getValue("outcome").jsonPrimitive.content)
        val exited = Json.parseToJsonElement(encodeLegacyQuit(LegacyQuitObservation.HelperExited(7))).jsonObject
        assertEquals(setOf("type", "stage", "exitCode"), exited.keys)
        assertEquals("HELPER_EXIT_FAILED", exited.getValue("type").jsonPrimitive.content)
        assertEquals("NORMAL_QUIT", exited.getValue("stage").jsonPrimitive.content)
        assertEquals("7", exited.getValue("exitCode").jsonPrimitive.content)
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<LegacyQuitObservation>(requested.replace("REQUESTED", "UNKNOWN_QUIT_OUTCOME"))
        }
    }
}
