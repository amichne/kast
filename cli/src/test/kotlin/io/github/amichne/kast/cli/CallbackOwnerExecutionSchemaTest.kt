package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import io.github.amichne.kast.protocol.wire.QueryCallbackOwnerExecutionWireFixture
import kotlinx.serialization.json.Json
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
        val reference =
            observed
                .getValue("properties")
                .jsonObject
                .getValue("owner_bindings")
                .jsonObject
                .getValue("items")
                .jsonObject
        assertEquals(setOf("\$ref"), reference.keys)
        assertEquals("#/\$defs/callbackBodyBinding", reference.getValue("\$ref").jsonPrimitive.content)
        val owner = fixture.definition("callbackBodyBinding")
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

    @Test
    fun `supplier owner list shares the canonical proof and keeps its size limit`() {
        val fixture = CallbackSchemaFixture()
        val owners =
            fixture
                .definition("callbackSupplierPartition")
                .getValue("properties")
                .jsonObject
                .getValue("owner_bindings")
                .jsonObject
        assertEquals(1000, owners.getValue("maxItems").jsonPrimitive.content.toInt())
        val reference = owners.getValue("items").jsonObject
        assertEquals(setOf("\$ref"), reference.keys)
        assertEquals("#/\$defs/callbackBodyBinding", reference.getValue("\$ref").jsonPrimitive.content)
    }

    @Test
    fun `shared owner proof rejects missing execution and default selection needs its exact enclosing reference`() {
        val fixture = CallbackSchemaFixture()
        val owner =
            Json.parseToJsonElement(QueryCallbackOwnerExecutionWireFixture().completeObservationExample())
                .jsonObject
                .getValue("flow")
                .jsonObject
                .getValue("owner_bindings")
                .jsonArray
                .single()
                .jsonObject
        val validator = fixture.validator("callbackBodyBinding")
        assertTrue(validator.validate(owner.toString(), InputFormat.JSON).isEmpty())
        val malformed = QueryCallbackOwnerExecutionWireFixture().missingOwnerExecutionExample()
        val missingExecution = Json.parseToJsonElement(malformed).jsonObject
        assertEquals(owner.keys - "execution", missingExecution.keys)
        missingExecution.forEach { (key, value) -> assertEquals(owner.getValue(key), value) }
        assertTrue(validator.validate(malformed, InputFormat.JSON).isNotEmpty())
        val selection =
            fixture.definition("callbackSupplier").getValue("properties").jsonObject.getValue("selection").jsonObject
        val default = variants(selection).getValue("DEFAULT")
        assertEquals(
            setOf("type", "declaration", "invocation_enclosing"),
            default.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        val enclosing = default.getValue("properties").jsonObject.getValue("invocation_enclosing").jsonObject
        assertEquals(setOf("\$ref"), enclosing.keys)
        assertEquals("#/\$defs/impactDeclarationReference", enclosing.getValue("\$ref").jsonPrimitive.content)
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
