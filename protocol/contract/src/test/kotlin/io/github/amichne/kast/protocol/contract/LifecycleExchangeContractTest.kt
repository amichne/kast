package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LifecycleExchangeContractTest {
    @Test
    fun `transport deadline contains bounded command stage cause and timing without a payload`() {
        val observation =
            IdeLifecycleExchangeFailure(
                IdeLifecycleExchangeCommand.STATUS,
                IdeLifecycleExchangeStage.READ_BODY,
                IdeLifecycleExchangeCause.DEADLINE_EXCEEDED,
                5001,
                5000,
            )
        val result = IdeLifecycleResult.Blocked(IdeLifecycleFailure.EXCHANGE_DEADLINE_EXCEEDED, exchange = observation)
        val shape = Json.parseToJsonElement(Json.encodeToString(IdeLifecycleResult.serializer(), result)).jsonObject
        assertEquals(setOf("type", "reason", "exchange"), shape.keys)
        assertEquals("blocked", shape.getValue("type").jsonPrimitive.content)
        assertEquals("EXCHANGE_DEADLINE_EXCEEDED", shape.getValue("reason").jsonPrimitive.content)
        val exchange = shape.getValue("exchange").jsonObject
        assertEquals(setOf("command", "stage", "cause", "elapsedMillis", "limitMillis"), exchange.keys)
        assertEquals("STATUS", exchange.getValue("command").jsonPrimitive.content)
        assertEquals("READ_BODY", exchange.getValue("stage").jsonPrimitive.content)
        assertEquals("DEADLINE_EXCEEDED", exchange.getValue("cause").jsonPrimitive.content)
        assertEquals("5001", exchange.getValue("elapsedMillis").jsonPrimitive.content)
        assertEquals("5000", exchange.getValue("limitMillis").jsonPrimitive.content)
    }
}
