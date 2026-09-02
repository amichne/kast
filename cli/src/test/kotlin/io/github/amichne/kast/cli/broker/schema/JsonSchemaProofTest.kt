package io.github.amichne.kast.cli.broker.schema

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class JsonSchemaProofTest {
    @Test
    fun `schema and admitted value proof constructors remain private`() {
        assertTrue(
            CompiledJsonSchema::class.java.declaredConstructors
                .filterNot { constructor -> constructor.isSynthetic }
                .all { constructor ->
                Modifier.isPrivate(constructor.modifiers)
            },
        )
        assertTrue(
            ValidatedJsonValue::class.java.declaredConstructors
                .filterNot { constructor -> constructor.isSynthetic }
                .all { constructor ->
                Modifier.isPrivate(constructor.modifiers)
            },
        )
    }

    @Test
    fun `admitted value carries the exact compiled schema digest`() {
        val document = Json.parseToJsonElement(
            """{"type":"object","additionalProperties":false,"properties":{}}""",
        ).jsonObject
        val schema = (
            NetworkntJsonSchemaCompiler.compile(document) as Refinement.Refined
        ).value
        val admitted = schema.admit(Json.parseToJsonElement("{}")) as Validation.Validated

        assertEquals(schema.digest, admitted.value.schemaDigest)
    }
}
