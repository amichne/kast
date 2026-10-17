package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LifecycleConnectionAttemptTest {
    @Test
    fun `server failure attributes dispatch command and actual timer outcome without payload`() {
        var now = 0L
        val attempt = LifecycleConnectionAttempt { now }
        attempt.dispatch(IdeLifecycleCommand.Status("host", "request"))
        now = 5_050_000_000
        val failure = attempt.failure(LifecycleConnectionCause.DEADLINE_EXCEEDED)
        assertEquals(IdeLifecycleExchangeCommand.STATUS, failure.command)
        assertEquals(LifecycleConnectionStage.DISPATCH, failure.stage)
        assertEquals(5050, failure.elapsedMillis)
        assertEquals(5000, failure.limitMillis)
        val encoded =
            Json.parseToJsonElement(Json.encodeToString(LifecycleConnectionFailure.serializer(), failure)).jsonObject
        assertEquals(setOf("command", "stage", "cause", "elapsedMillis", "limitMillis"), encoded.keys)
        assertEquals("DEADLINE_EXCEEDED", encoded.getValue("cause").jsonPrimitive.content)
        assertEquals(LifecycleConnectionCause.IO_FAILURE, attempt.failure(LifecycleConnectionCause.IO_FAILURE).cause)
    }
}
