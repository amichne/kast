package io.github.amichne.kast.protocol.wire

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryCallbackRefinementWireTest {
    private val fixture = QueryCallbackRefinementWireFixture()

    @Test
    fun `transport requires explicit scan and forwardings even when arrays are empty`() {
        val wire = fixture.base()
        val raw = wireJson.encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire).jsonObject
        val flow = raw.getValue("flow").jsonObject
        assertEquals("INCOMPLETE", flow.getValue("scan").jsonPrimitive.content)
        assertEquals(
            emptyList<Any>(),
            flow.getValue("invocations").jsonArray.single().jsonObject.getValue("forwardings").jsonArray.toList(),
        )
        val encoded = wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), wire)
        val absentScan = withoutRequiredField(encoded, ",\"scan\":\"INCOMPLETE\"")
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(QueryCallbackObservationWireDocument.serializer(), absentScan)
        }
        val invocation = fixture.flow(wire).invocations.single()
        val encodedInvocation = wireJson.encodeToString(QueryCallbackInvocationWireDocument.serializer(), invocation)
        val absentForwardings = withoutRequiredField(encodedInvocation, ",\"forwardings\":[]")
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(QueryCallbackInvocationWireDocument.serializer(), absentForwardings)
        }
    }

    @Test
    fun `default and direct supply variants encode independently authored source shapes`() {
        val occurrence = fixture.occurrence("Boundary.kt", 9, 26)
        for ((supply, resource) in
            listOf(
                QueryCallbackBodySupplyWireDocument.DefaultParameter(occurrence) to "callback-body-supply-default.json",
                QueryCallbackBodySupplyWireDocument.DirectInvocation(occurrence) to "callback-body-supply-direct.json",
            )) assertEquals(
            wireJson.parseToJsonElement(checkNotNull(javaClass.getResource("/query/$resource")).readText()),
            wireJson.encodeToJsonElement(QueryCallbackBodySupplyWireDocument.serializer(), supply),
        )
    }

    private fun withoutRequiredField(encoded: String, field: String): String {
        assertEquals(2, encoded.split(field).size, "The required field must occur exactly once before removing it")
        return encoded.replace(field, "")
    }
}
