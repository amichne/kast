package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Copilot registers the generated object root directly, retaining each discriminated alternative. */
class CopilotInputSchemaCompatibilityTest {
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

    @Test
    fun `source text variants remain disjoint in the generated provider schema`() {
        val schema = PublicToolContract.parameters(PublicToolIdentity.READ_SOURCE)
        assertEquals("object", schema.getValue("type").toString().trim('"'))
        val textVariants = schema.getValue("$" + "defs").jsonObject.getValue("SourceText")
        assertTrue(textVariants.jsonObject.getValue("anyOf").jsonArray.size == 3)
        val examples = PublicToolContract.examples(PublicToolIdentity.READ_SOURCE)
        assertAdmits(schema, examples.examples.getValue("callableBody").value.toString())
        assertRejects(schema, examples.invalidExamples.getValue("mixedTextVariant").value.toString())
        assertRejects(schema, examples.invalidExamples.getValue("missingTextType").value.toString())
    }

    @Test
    fun `query action variants retain their union rather than an optional property bag`() {
        val schema = PublicToolContract.parameters(PublicToolIdentity.QUERY_SYMBOLS)
        val action = schema.getValue("$" + "defs").jsonObject.getValue("Action").jsonObject
        assertEquals(3, action.getValue("anyOf").jsonArray.size)
        assertRejects(
            schema,
            PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS)
                .invalidExamples
                .getValue("mixedAction")
                .value
                .toString(),
        )
    }

    private fun assertAdmits(schema: JsonObject, raw: String) {
        assertTrue(registry.getSchema(schema.toString()).validate(raw, InputFormat.JSON).isEmpty())
    }

    private fun assertRejects(schema: JsonObject, raw: String) {
        assertTrue(registry.getSchema(schema.toString()).validate(raw, InputFormat.JSON).isNotEmpty())
    }
}
