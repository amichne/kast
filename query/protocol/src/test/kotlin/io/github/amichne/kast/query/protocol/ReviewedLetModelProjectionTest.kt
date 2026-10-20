package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.CompilerReceiverWireDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureWireDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueModelDocument
import io.github.amichne.kast.relation.contract.ReviewedValueModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ReviewedLetModelProjectionTest {
    @Test
    fun `let projection keeps its reviewed callable artifact and rule identity`() {
        val document = ReviewedValueModel.Let.impactDocument().modelValue()
        assertInstanceOf(ImpactReviewedValueModelDocument.Let::class.java, document)
        assertEquals(
            CompilerSignatureWireDocument.Function(
                "kotlin.let",
                CompilerReceiverWireDocument.Present("T"),
                emptyList(),
                listOf("(T)->R"),
                2,
            ),
            document.callable,
        )
        assertEquals("scope.let.invoke-block-value", document.reference.rule.value)
        assertEquals("kotlin-stdlib-value-flow", document.reference.model.id.value)
        assertEquals(
            "source-sha256:${ImpactValueModelProjectionCase.SOURCE_SHA}",
            document.reference.model.provenance.value,
        )
        assertEquals(ImpactValueModelProjectionCase.BINARY_SHA, document.artifact.binaryDigest.value)
        assertEquals(ImpactValueModelProjectionCase.SOURCE_SHA, document.artifact.sourceDigest.value)
        assertEquals(ImpactValueModelProjectionCase.VERSION, document.artifact.version.value)
        val encoded = Json.encodeToJsonElement(ImpactReviewedValueModelDocument.serializer(), document).jsonObject
        assertEquals(setOf("type", "reference", "artifact", "callable"), encoded.keys)
        assertEquals("LET", encoded.getValue("type").jsonPrimitive.content)
    }
}
