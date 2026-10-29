package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedScopeTest {
    @Test
    fun `reviewed return rejects missing invocation mismatched owner artifact and overload`() {
        val f = ImpactReviewedScopeFixture()
        val changed =
            listOf(
                f.binding.copy(invocationEvidence = ImpactReviewedInvocationEvidenceDocument.Unestablished),
                f.binding.copy(body = f.body.copy(range = f.range(41, 80))),
                f.binding.copy(supplierRange = f.range(29, 90)),
                f.binding.copy(artifacts = f.artifacts.copy(binaryArtifactSha256 = f.base.text("f".repeat(64)))),
                f.binding.copy(artifacts = f.artifacts.copy(sourceArtifactSha256 = f.base.text("f".repeat(64)))),
                f.binding.copy(
                    artifacts =
                        f.artifacts.copy(
                            functionLines = ImpactReviewedSourceLinesDocument(f.base.offset(99), f.base.offset(105))
                        )
                ),
                f.binding.copy(
                    selected = f.binding.selected.copy(receiver = f.base.text("kotlin/collections/Iterable<T>"))
                ),
                f.binding.copy(selected = f.binding.selected.copy(formalIndex = f.base.offset(1))),
                f.binding.copy(rule = ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1),
                f.binding.copy(
                    invocationEvidence =
                        ImpactReviewedInvocationEvidenceDocument.Declared(
                            f.declared.copy(
                                contract = f.declared.contract.copy(classDigest = f.base.text("f".repeat(64)))
                            )
                        )
                ),
            )
        for (binding in changed) {
            assertFalse(binding.admits(binding.enclosing), binding.toString())
            assertFalse(
                f.transfer
                    .copy(binding = ImpactReviewedValueRelationshipDocument.fromBinding(binding, binding.semantics))
                    .admitsEvidence()
            )
        }
        for (source in
            listOf(
                f.source.copy(executionOwner = ImpactExecutionOwnerDocument.ReviewedCallback(f.binding)),
                f.source.copy(range = f.range(10, 20)),
                f.source.copy(role = ImpactValueRoleDocument.ExpressionResult),
            )) assertFalse(f.transfer.copy(source = source).admitsEvidence())
        assertFalse(f.transfer.copy(target = f.target.copy(valuePath = EmptyImpactValuePath)).admitsEvidence())
        assertTrue(f.transfer.admitsEvidence())
    }

    @Test
    fun `let return encodes separate invocation and return authority with exact source spans`() {
        val f = ImpactReviewedScopeFixture()
        assertTrue(f.transfer.admitsEvidence())
        val encoded = Json.encodeToString(f.transfer)
        val transfer = Json.parseToJsonElement(encoded).jsonObject
        val binding = transfer.getValue("binding").jsonObject
        assertEquals("KOTLIN_2_3_0_LET_V1", binding.getValue("rule").jsonPrimitive.content)
        assertEquals("CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT", binding.getValue("semantics").jsonPrimitive.content)
        assertEquals("NOT_ESTABLISHED", binding.getValue("actualExecution").jsonPrimitive.content)
        assertEquals("NOT_ESTABLISHED", binding.getValue("invocationCount").jsonPrimitive.content)
        val invocation = binding.getValue("invocationEvidence").jsonObject
        assertEquals(setOf("type", "binding"), invocation.keys)
        assertEquals("COMPILER_DECLARED", invocation.getValue("type").jsonPrimitive.content)
        val declared = invocation.getValue("binding").jsonObject
        assertEquals("UNSPECIFIED", declared.getValue("resultEffect").jsonPrimitive.content)
        assertEquals(
            "EXACTLY_ONCE",
            declared.getValue("contract").jsonObject.getValue("invocationKind").jsonPrimitive.content,
        )
        val artifacts = binding.getValue("artifacts").jsonObject
        assertEquals(
            setOf(
                "sourceEntry",
                "binaryArtifactSha256",
                "sourceArtifactSha256",
                "sourceEntrySha256",
                "facadeSha256",
                "implementationSha256",
                "functionLines",
            ),
            artifacts.keys,
        )
        assertEquals("commonMain/kotlin/util/Standard.kt", artifacts.getValue("sourceEntry").jsonPrimitive.content)
        assertEquals("114", artifacts.getValue("functionLines").jsonObject.getValue("first").jsonPrimitive.content)
        assertEquals("119", artifacts.getValue("functionLines").jsonObject.getValue("last").jsonPrimitive.content)
        assertEquals(
            transfer.getValue("source").jsonObject.getValue("valuePath"),
            transfer.getValue("target").jsonObject.getValue("valuePath"),
        )
        assertEquals(f.transfer, Json.decodeFromString<ImpactReviewedValueTransferDocument>(encoded))
    }

    @Test
    fun `unknown rule source authority and false execution claims fail decoding`() {
        val encoded = Json.encodeToString(ImpactReviewedScopeFixture().binding)
        for (malformed in
            listOf(
                encoded.replace("KOTLIN_2_3_0_LET_V1", "CUSTOM_RULE"),
                encoded.replace("commonMain/kotlin/util/Standard.kt", "wrong/source.kt"),
                encoded.replace("COMPILER_DECLARED", "NAME_ONLY"),
                encoded.replace("\"actualExecution\":\"NOT_ESTABLISHED\"", "\"actualExecution\":\"EXECUTED\""),
                encoded.replace("\"invocationCount\":\"NOT_ESTABLISHED\"", "\"invocationCount\":\"EXACTLY_ONCE\""),
            )) {
            assertNotEquals(encoded, malformed)
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString<ImpactReviewedValueBindingDocument>(malformed)
            }
        }
    }
}
