package io.github.amichne.kast.appserver.schema

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonSchemaViolationEvidenceTest {
    private val schema =
        (NetworkntJsonSchemaCompiler.compile(
                Json.parseToJsonElement(
                        """
                {"type":"object","additionalProperties":false,
                 "properties":{"identity":{"type":"string","pattern":"^valid$"}},
                 "required":["identity"]}
                """
                    )
                    .jsonObject
            ) as Refinement.Refined)
            .value

    @Test
    fun `output rejection retains finite field and keyword without source payload`() {
        val rejected =
            schema.admit(Json.parseToJsonElement("""{"identity":"secret-source-payload"}""")) as Validation.Rejected
        val evidence = Json.encodeToString(JsonSchemaViolationEvidence.from(rejected.failures).toDocument())
        assertEquals("""{"observations":[{"keyword":"PATTERN","field":"IDENTITY"}]}""", evidence)
        assertFalse(evidence.contains("secret"))
    }

    @Test
    fun `unknown instance property names are never exported`() {
        val rejected =
            schema.admit(Json.parseToJsonElement("""{"identity":"valid","private-reference-token":7}"""))
                as Validation.Rejected
        val evidence = Json.encodeToString(JsonSchemaViolationEvidence.from(rejected.failures).toDocument())
        assertEquals("""{"observations":[{"keyword":"ADDITIONAL_PROPERTIES","field":"UNKNOWN"}]}""", evidence)
        assertFalse(evidence.contains("private-reference"))
    }

    @Test
    fun `successful validation retains admission proof and has no rejection evidence`() {
        val admitted = schema.admit(Json.parseToJsonElement("""{"identity":"valid"}""")) as Validation.Validated
        assertEquals(schema.digest, admitted.value.schemaDigest)
        assertTrue(schema.constraintViolations(admitted.value.element).isEmpty())
    }
}
