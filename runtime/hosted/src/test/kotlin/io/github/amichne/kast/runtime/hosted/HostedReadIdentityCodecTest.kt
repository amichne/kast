package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadTraceIdentity
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HostedReadIdentityCodecTest {
    @Test
    fun `read joins preserve connection identity and the surrounding execution clock`() {
        var now = 0L
        val joins = mutableListOf<HostedCorrelatedReadIdentity>()
        val transports = mutableListOf<HostedTransportObservation>()
        val observer =
            object : HostedEndpointObserver {
                override fun observe(stage: HostedEndpointStage, outcome: HostedEndpointOutcome) = Unit

                override fun readIdentity(observation: HostedCorrelatedReadIdentity) {
                    joins += observation
                }

                override fun transport(observation: HostedTransportObservation) {
                    transports += observation
                }
            }
        val trace = HostedTransportTrace(observer, { now })
        trace.enter(HostedTransportStage.EXECUTION)
        val first = HostedReadTraceIdentity.fromBoundary(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val retry = HostedReadTraceIdentity.fromBoundary(UUID.fromString("00000000-0000-0000-0000-000000000002"))
        now = 11L
        trace.readIdentity(first)
        now = 19L
        trace.readIdentity(retry)
        trace.emit(HostedEndpointOutcome.COMPLETED)
        assertEquals(listOf(first.value.toString(), retry.value.toString()), joins.map { it.readId })
        assertEquals(setOf(transports.first().connectionId), joins.map { it.connectionId }.toSet())
        assertEquals(19L, transports.last().elapsedNanos)
        assertNotEquals(first.value, retry.value)
    }

    @Test
    fun `join encoding contains only the two independent required identities`() {
        val encoded = Json.encodeToString(HostedCorrelatedReadIdentity("connection", "read"))
        val fields = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("connectionId", "readId"), fields.keys)
        assertEquals("connection", fields.getValue("connectionId").jsonPrimitive.content)
        assertEquals("read", fields.getValue("readId").jsonPrimitive.content)
        val missingRead = encoded.replace(",\"readId\":\"read\"", "")
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<HostedCorrelatedReadIdentity>(missingRead)
        }
    }
}
