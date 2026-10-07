package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.wire.QueryCallbackWireFixture
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallbackForwardingGraphSchemaTest {
    @Test
    fun `generated forwarding evidence requires finite closed alternatives and bounds the graph inventory`() {
        val observed = observedCallbackSchema()
        assertTrue(observed.getValue("required").jsonArray.any { it.jsonPrimitive.content == "forwarding" })
        val evidence = observed.getValue("properties").jsonObject.getValue("forwarding").jsonObject
        assertEquals(
            "type",
            evidence.getValue("discriminator").jsonObject.getValue("propertyName").jsonPrimitive.content,
        )
        val variants =
            evidence
                .getValue("anyOf")
                .jsonArray
                .map { it.jsonObject }
                .associateBy { variant ->
                    variant
                        .getValue("properties")
                        .jsonObject
                        .getValue("type")
                        .jsonObject
                        .getValue("enum")
                        .jsonArray
                        .single()
                        .jsonPrimitive
                        .content
                }
        assertEquals(setOf("INVOCATION_ROUTES", "EXHAUSTED_GRAPH"), variants.keys)
        assertEquals(setOf("type"), variants.getValue("INVOCATION_ROUTES").getValue("properties").jsonObject.keys)
        val graph = variants.getValue("EXHAUSTED_GRAPH")
        assertEquals(setOf("type", "root", "formals", "forwardings"), graph.getValue("properties").jsonObject.keys)
        assertEquals(
            setOf("type", "root", "formals", "forwardings"),
            graph.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        for (variant in variants.values) assertEquals(JsonPrimitive(false), variant["additionalProperties"])
        val formals = graph.getValue("properties").jsonObject.getValue("formals").jsonObject
        assertEquals(JsonPrimitive(1), formals["minItems"])
        assertEquals(JsonPrimitive(1000), formals["maxItems"])
        assertEquals(JsonPrimitive(true), formals["uniqueItems"])
        val definitions = installedServerOutputSchema(CanonicalOperation.QUERY_RUN).getValue("\$defs").jsonObject
        assertTrue("callbackObservation" in definitions)
    }

    @Test
    fun `forwarding schema admits serializer examples and rejects missing unknown or extra graph facts`() {
        val evidence = observedCallbackSchema().getValue("properties").jsonObject.getValue("forwarding").jsonObject
        val validator =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(evidence.toString())
        val fixture = QueryCallbackWireFixture()
        for (raw in fixture.forwardingEvidenceExamples()) {
            assertTrue(validator.validate(raw, InputFormat.JSON).isEmpty(), raw)
        }
        for (raw in
            fixture.malformedForwardingEvidenceExamples() + fixture.malformedForwardingInventoryExamples()) assertTrue(
            validator.validate(raw, InputFormat.JSON).isNotEmpty(),
            raw,
        )
    }

    private fun observedCallbackSchema(): JsonObject =
        generatedRequestSchema(CanonicalQueryCliDocuments.callbackObservationSerializer)
            .getValue("properties")
            .jsonObject
            .getValue("flow")
            .jsonObject
            .getValue("anyOf")
            .jsonArray
            .map { it.jsonObject }
            .single { variant ->
                variant
                    .getValue("properties")
                    .jsonObject
                    .getValue("type")
                    .jsonObject
                    .getValue("enum")
                    .jsonArray
                    .single()
                    .jsonPrimitive
                    .content == "OBSERVED"
            }
}
