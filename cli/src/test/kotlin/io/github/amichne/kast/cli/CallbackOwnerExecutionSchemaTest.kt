package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import io.github.amichne.kast.protocol.wire.QueryCallbackOwnerExecutionWireFixture
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallbackOwnerExecutionSchemaTest {
    @Test
    fun `generated callback schema preserves recursive execution alternatives and admits composed evidence`() {
        val fixture = CallbackSchemaFixture()
        val schema = fixture.definition("callbackObservation")
        val flow = schema.getValue("properties").jsonObject.getValue("flow").jsonObject
        val observed = variants(flow).getValue("OBSERVED")
        val owner =
            observed
                .getValue("properties")
                .jsonObject
                .getValue("owner_bindings")
                .jsonObject
                .getValue("items")
                .jsonObject
        assertTrue("execution" in owner.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val execution = owner.getValue("properties").jsonObject.getValue("execution").jsonObject
        val alternatives = variants(execution)
        assertEquals(setOf("UNPROVEN", "OBSERVED", "IMMUTABLE"), alternatives.keys)
        assertEquals(setOf("type"), alternatives.getValue("UNPROVEN").getValue("properties").jsonObject.keys)
        assertEquals(
            setOf("type", "flow"),
            alternatives.getValue("OBSERVED").getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        val immutable = fixture.definition("immutableCallbackFlow")
        assertTrue("owner_bindings" in immutable.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val validator = fixture.validator("callbackObservation")
        val examples = QueryCallbackOwnerExecutionWireFixture()
        for (raw in
            listOf(
                examples.completeObservationExample(),
                examples.immutableObservationExample(),
                examples.nestedImmutableObservationExample(),
            )) assertTrue(
            validator.validate(raw, InputFormat.JSON).isEmpty(),
            raw,
        )
    }

    private fun variants(schema: JsonObject) =
        schema
            .getValue("anyOf")
            .jsonArray
            .map { it.jsonObject }
            .associateBy {
                it.getValue("properties")
                    .jsonObject
                    .getValue("type")
                    .jsonObject
                    .getValue("enum")
                    .jsonArray
                    .single()
                    .jsonPrimitive
                    .content
            }
}
