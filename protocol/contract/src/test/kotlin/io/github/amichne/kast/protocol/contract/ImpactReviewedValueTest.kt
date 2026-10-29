package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedValueTest {
    @Test
    fun `reviewed path encodes separate provenance exact artifact evidence and conditional semantics`() {
        val fixture = ImpactReviewedValueFixture()
        val step: ImpactPathStepDocument = ImpactPathStepDocument.ReviewedLibrary(fixture.transfer)
        val encoded = Json.parseToJsonElement(Json.encodeToString(step)).jsonObject
        assertEquals(setOf("type", "transfer"), encoded.keys)
        assertEquals("REVIEWED_LIBRARY", encoded.getValue("type").jsonPrimitive.content)
        val transfer = encoded.getValue("transfer").jsonObject
        assertEquals(setOf("source", "target", "binding"), transfer.keys)
        val binding = transfer.getValue("binding").jsonObject
        assertBindingShape(binding)
        val target = transfer.getValue("target").jsonObject
        assertEquals(
            listOf("ELEMENT", "FIELD"),
            target.getValue("valuePath").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content },
        )
        assertEquals(
            "REVIEWED_CALLBACK",
            transfer
                .getValue("source")
                .jsonObject
                .getValue("executionOwner")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
        assertTrue(fixture.transfer.admitsEvidence())
    }

    private fun assertBindingShape(binding: JsonObject) {
        assertEquals(
            setOf(
                "rule",
                "basis",
                "enclosing",
                "body",
                "supplierRange",
                "selected",
                "artifacts",
                "semantics",
                "actualExecution",
                "invocationCount",
                "invocationEvidence",
            ),
            binding.keys,
        )
        assertEquals(
            "CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT",
            binding.getValue("semantics").jsonPrimitive.content,
        )
        assertEquals("NOT_ESTABLISHED", binding.getValue("actualExecution").jsonPrimitive.content)
        assertEquals("NOT_ESTABLISHED", binding.getValue("invocationCount").jsonPrimitive.content)
        assertEquals("KOTLIN_2_4_21_ITERABLE_MAP_V1", binding.getValue("rule").jsonPrimitive.content)
        val selected = binding.getValue("selected").jsonObject
        assertEquals("kotlin.collections.map", selected.getValue("qualifiedIdentity").jsonPrimitive.content)
        assertEquals("0", selected.getValue("formalIndex").jsonPrimitive.content)
    }

    @Test
    fun `reviewed wire rejects lost field wrong return source foreign basis and changed dependencies`() {
        val fixture = ImpactReviewedValueFixture()
        val transfer = fixture.transfer
        val altered =
            listOf(
                transfer.copy(
                    target =
                        transfer.target.copy(
                            valuePath = fixture.bounded(listOf(ImpactValuePathSegmentDocument.Element))
                        )
                ),
                transfer.copy(source = transfer.source.copy(role = ImpactValueRoleDocument.ExpressionResult)),
                transfer.copy(source = transfer.source.copy(range = fixture.range(10, 20))),
                transfer.copy(
                    target =
                        transfer.target.copy(
                            enclosing = transfer.target.enclosing.copy(file = fixture.text("/workspace/Other.kt"))
                        )
                ),
            )
        for (invalid in altered) assertFalse(invalid.admitsEvidence())
        for (changed in changedBindings(fixture)) {
            assertFalse(changed.admits(changed.enclosing))
            assertFalse(
                transfer
                    .copy(
                        binding = changed,
                        source =
                            transfer.source.copy(
                                executionOwner = ImpactExecutionOwnerDocument.ReviewedCallback(changed)
                            ),
                    )
                    .admitsEvidence()
            )
        }
        assertTrue(transfer.admitsEvidence())
    }

    private fun changedBindings(fixture: ImpactReviewedValueFixture): List<ImpactReviewedValueBindingDocument> {
        val transfer = fixture.transfer
        return listOf(
            transfer.binding.copy(
                artifacts =
                    org.junit.jupiter.api.Assertions.assertInstanceOf(
                            ImpactReviewedMapArtifactsDocument::class.java,
                            transfer.binding.artifacts,
                        )
                        .copy(binaryArtifactSha256 = fixture.text("f".repeat(64)))
            ),
            transfer.binding.copy(
                artifacts =
                    org.junit.jupiter.api.Assertions.assertInstanceOf(
                            ImpactReviewedMapArtifactsDocument::class.java,
                            transfer.binding.artifacts,
                        )
                        .copy(sourceArtifactSha256 = fixture.text("f".repeat(64)))
            ),
            transfer.binding.copy(
                body = transfer.binding.body.copy(compilerIdentity = fixture.text("name-only-callback"))
            ),
            transfer.binding.copy(
                selected =
                    transfer.binding.selected.copy(
                        declaration =
                            transfer.binding.selected.declaration.copy(
                                compilerIdentity = fixture.text("canonical-signature-sha256-v1|" + "f".repeat(64))
                            )
                    )
            ),
            transfer.binding.copy(
                selected = transfer.binding.selected.copy(formalIndex = ProtocolOffset.parse(1).reviewedValue())
            ),
            transfer.binding.copy(
                basis =
                    ImpactSemanticBasisDocument.Published(
                        fixture.text("/workspace"),
                        ImpactEvidenceRevisionDocument.parse(8).reviewedValue(),
                    )
            ),
        )
    }

    @Test
    fun `required identity fields and finite knowledge enums reject missing or invented facts`() {
        val encoded = Json.encodeToString(ImpactReviewedValueFixture().binding)
        val malformed =
            listOf(
                encoded.replace("NOT_ESTABLISHED", "EXACTLY_ONCE"),
                encoded.replace("KOTLIN_2_4_21_ITERABLE_MAP_V1", "NAME_ONLY_MAP"),
                encoded.replace(",\"formalIndex\":0", ""),
                encoded.replace(
                    ",\"sourceArtifactSha256\":\"1e29c0668b0aa555e7b154e5da2e8094e1b755cfee25737839195bbbdad8a4a2\"",
                    "",
                ),
            )
        for (raw in malformed) assertThrows(SerializationException::class.java) {
            Json.decodeFromString(ImpactReviewedValueBindingDocument.serializer(), raw)
        }
    }
}

private fun <T, F> io.github.amichne.kast.kernel.Refinement<T, F>.reviewedValue(): T =
    when (this) {
        is io.github.amichne.kast.kernel.Refinement.Refined -> value
        is io.github.amichne.kast.kernel.Refinement.Rejected -> error("Invalid test value: $failure")
    }
