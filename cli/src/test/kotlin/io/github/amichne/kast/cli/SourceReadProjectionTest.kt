package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceReadProjectionTest {
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    private val identity = PublicToolIdentity.READ_SOURCE

    @Test
    fun `published source examples match generated schema and typed admission`() {
        val reference = Json.parseToJsonElement(mintlifyCallableReference().value).jsonObject
        val media =
            reference
                .getValue("paths")
                .jsonObject
                .getValue("/callables/read_source")
                .jsonObject
                .getValue("post")
                .jsonObject
                .getValue("requestBody")
                .jsonObject
                .getValue("content")
                .jsonObject
                .getValue("application/json")
                .jsonObject
        val examples = media.getValue("examples").jsonObject
        val schema = PublicToolContract.parameters(identity)
        assertEquals(setOf("exactSymbol", "callableBody"), examples.keys)
        examples.values.forEach { example ->
            val raw = example.jsonObject.getValue("value")
            assertTrue(registry.getSchema(schema.toString()).validate(raw.toString(), InputFormat.JSON).isEmpty())
            val admitted = PublicToolContract.admit(identity, raw)
            assertTrue(admitted is Refinement.Refined, admitted.toString())
            assertTrue((admitted as Refinement.Refined).value.canonical is PublicToolCanonical.Source)
        }
    }

    @Test
    fun `retired source shortcut and mixed variants reject in the generated contract`() {
        val schema = PublicToolContract.parameters(identity)
        val invalid = PublicToolContract.examples(identity).invalidExamples.values.map { it.value }
        invalid.forEach { raw ->
            assertTrue(registry.getSchema(schema.toString()).validate(raw.toString(), InputFormat.JSON).isNotEmpty())
            assertTrue(PublicToolContract.admit(identity, raw) is Refinement.Rejected)
        }
    }
}
