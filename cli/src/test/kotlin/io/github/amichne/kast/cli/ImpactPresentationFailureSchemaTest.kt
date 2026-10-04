package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.ImpactAccountingFailure
import io.github.amichne.kast.protocol.contract.ImpactPresentationBudgetCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationCollectionCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationCountCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactPresentationFingerprintCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationModelCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationOffsetCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationTextCause
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactPresentationFailureSchemaTest {
    @Test
    fun `impact presentation failure schema closes each refinement boundary and finite cause`() {
        val document = generatedRequestSchema(ImpactPresentationFailureDocument.serializer())
        val variants = document.getValue("anyOf").jsonArray
        assertEquals(
            setOf(
                "ACCOUNTING",
                "TEXT",
                "OFFSET",
                "MODEL",
                "COLLECTION",
                "DOMAIN_FINGERPRINT",
                "COUNT",
                "BUDGET",
                "DOMAIN_PROJECTION_REJECTED",
            ),
            variants
                .map {
                    it.jsonObject
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
                .toSet(),
        )
        for (variant in variants) {
            assertEquals("false", variant.jsonObject.getValue("additionalProperties").jsonPrimitive.content)
            assertTrue(variant.jsonObject.getValue("required").jsonArray.any { it.jsonPrimitive.content == "type" })
        }
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(document.toString())
        val cases = examples()
        for (case in cases) {
            val raw = Json.encodeToString(ImpactPresentationFailureDocument.serializer(), case)
            assertTrue(schema.validate(raw, InputFormat.JSON).isEmpty(), raw)
            assertTrue(schema.validate(raw.dropLast(1) + ",\"detail\":\"untyped\"}", InputFormat.JSON).isNotEmpty())
        }
        val unknownCause =
            Json.encodeToString(ImpactPresentationFailureDocument.serializer(), cases.first())
                .replace("BLANK", "UNKNOWN")
        assertTrue(schema.validate(unknownCause, InputFormat.JSON).isNotEmpty())
    }

    private fun examples(): List<ImpactPresentationFailureDocument> {
        val cases =
            listOf(
                ImpactPresentationFailureDocument.Text(ImpactPresentationTextCause.BLANK),
                ImpactPresentationFailureDocument.Offset(ImpactPresentationOffsetCause.NEGATIVE),
                ImpactPresentationFailureDocument.Model(ImpactPresentationModelCause.UNSUPPORTED_FORMAT),
                ImpactPresentationFailureDocument.Collection(ImpactPresentationCollectionCause.TOO_LARGE),
                ImpactPresentationFailureDocument.DomainFingerprint(
                    ImpactPresentationFingerprintCause.INVALID_FINGERPRINT
                ),
                ImpactPresentationFailureDocument.Count(ImpactPresentationCountCause.NEGATIVE),
                ImpactPresentationFailureDocument.Budget(ImpactPresentationBudgetCause.NOT_POSITIVE),
                ImpactPresentationFailureDocument.Accounting(ImpactAccountingFailure.PAGE_COUNT_MISMATCH),
                ImpactPresentationFailureDocument.DomainProjectionRejected,
            )
        return cases
    }
}
