package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedMapInputNegativeTest {
    @Test
    fun `map output correspondence cannot admit a whole receiver to callback input edge`() {
        val f = ImpactReviewedValueFixture()
        val receiver = receiver(f)
        val formal = formal(f)
        assertTrue(receiver.admitsExecutionOwner(), "The source must be a valid declaration-owned receiver")
        assertTrue(formal.admitsExecutionOwner(), "The target must be a valid parameter of the reviewed callback")
        assertFalse(
            ImpactReviewedValueTransferDocument(receiver, formal, f.transfer.binding).admitsEvidence(),
            "A callback-result rule cannot establish collection identity with a callback parameter",
        )
    }

    @Test
    fun `a whole map receiver cannot bypass the callback body into the call result`() {
        val f = ImpactReviewedValueFixture()
        val receiver = receiver(f)
        val result = f.target.copy(valuePath = EmptyImpactValuePath)
        assertTrue(receiver.admitsExecutionOwner())
        assertTrue(result.admitsExecutionOwner())
        assertFalse(
            ImpactReviewedValueTransferDocument(receiver, result, f.transfer.binding).admitsEvidence(),
            "No reviewed input-to-output shortcut exists",
        )
    }

    @Test
    fun `existing map output retains its distinct rule and exact historical wire fields`() {
        val f = ImpactReviewedValueFixture()
        assertTrue(f.transfer.admitsEvidence())
        val encoded = Json.parseToJsonElement(Json.encodeToString(f.transfer)).jsonObject
        assertEquals(setOf("source", "target", "binding"), encoded.keys)
        val binding = encoded.getValue("binding").jsonObject
        assertEquals("KOTLIN_2_4_21_ITERABLE_MAP_V1", binding.getValue("rule").jsonPrimitive.content)
        assertEquals(
            "CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT",
            binding.getValue("semantics").jsonPrimitive.content,
        )
        assertEquals("NOT_ESTABLISHED", binding.getValue("actualExecution").jsonPrimitive.content)
        assertEquals("NOT_ESTABLISHED", binding.getValue("invocationCount").jsonPrimitive.content)
        assertEquals(
            listOf(ImpactValuePathSegmentDocument.Element) + f.source.valuePath.values,
            f.target.valuePath.values,
        )
    }

    private fun receiver(f: ImpactReviewedValueFixture): ImpactValueSiteReferenceDocument =
        f.source.copy(
            range = f.range(30, 35),
            role =
                ImpactValueRoleDocument.Receiver(
                    ImpactInvocationReferenceDocument(f.binding.supplierRange, f.binding.selected.declaration),
                    ImpactReceiverKindDocument.EXTENSION,
                ),
            executionOwner = ImpactExecutionOwnerDocument.Declaration,
            valuePath = EmptyImpactValuePath,
        )

    private fun formal(f: ImpactReviewedValueFixture): ImpactValueSiteReferenceDocument {
        val owner = ImpactExecutionOwnerDocument.ReviewedCallback(f.binding)
        val parameter =
            ImpactFormalParameterDocument(
                f.binding.enclosing,
                (ProtocolOffset.parse(0) as io.github.amichne.kast.kernel.Refinement.Refined).value,
                f.text("item"),
                f.range(40, 45),
                owner,
            )
        return f.source.copy(
            range = parameter.range,
            role = ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Parameter(parameter)),
            executionOwner = owner,
            valuePath = EmptyImpactValuePath,
        )
    }
}
