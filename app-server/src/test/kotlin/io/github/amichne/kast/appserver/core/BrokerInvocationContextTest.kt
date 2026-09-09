package io.github.amichne.kast.appserver.core

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BrokerInvocationContextTest {
    @Test fun `distinct admitted invocation tuples cannot collide through delimiters`(@TempDir directory: Path) {
        val root = directory.toRealPath()
        val first = (BrokerInvocationContext.admit("thread:turn", "turn", "call", root) as Refinement.Refined).value
        val second = (BrokerInvocationContext.admit("thread", "turn:turn", "call", root) as Refinement.Refined).value
        assertNotEquals(first.invocationId, second.invocationId)
        assertEquals(JsonArray(listOf("thread:turn", "turn", "call").map(::JsonPrimitive)), Json.parseToJsonElement(first.invocationId))
    }
}
