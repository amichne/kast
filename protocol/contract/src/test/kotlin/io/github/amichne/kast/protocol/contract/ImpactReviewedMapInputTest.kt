package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedMapInputTest {
    @Test
    fun `input relations reject a result shortcut and a mismatched callback owner`() {
        val f = ImpactReviewedValueFixture()
        val input = input(f, ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER)
        assertTrue(input.source.admitsExecutionOwner())
        assertTrue(input.target.admitsExecutionOwner())
        assertFalse(input.copy(target = f.target.copy(valuePath = EmptyImpactValuePath)).admitsEvidence())
        val foreignOwner =
            ImpactExecutionOwnerDocument.ReviewedCallback(
                f.binding.copy(body = f.binding.body.copy(range = f.range(41, 80)))
            )
        assertFalse(input.copy(target = input.target.copy(executionOwner = foreignOwner)).admitsEvidence())
        assertFalse(
            input
                .copy(
                    binding =
                        input.binding.copy(
                            relation =
                                ImpactReviewedValueRelationDocument.CallbackCorrespondence(
                                    ImpactReviewedValueSemanticsDocument.CONDITIONAL_INPUT_ELEMENT_TO_CALLBACK_PARAMETER
                                )
                        )
                )
                .admitsEvidence()
        )
    }

    @Test
    fun `whole receiver contributes derived callback content without an execution claim`() {
        val f = ImpactReviewedValueFixture()
        val input = input(f, ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER)
        assertTrue(input.admitsEvidence())
        val encoded = Json.parseToJsonElement(Json.encodeToString(input)).jsonObject
        assertEquals(setOf("source", "target", "binding"), encoded.keys)
        val relation = encoded.getValue("binding").jsonObject.getValue("relation").jsonObject
        val authority = encoded.getValue("binding").jsonObject.getValue("authority").jsonObject
        assertEquals(
            "CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER",
            relation.getValue("semantics").jsonPrimitive.content,
        )
        assertEquals("NOT_ESTABLISHED", authority.getValue("actualExecution").jsonPrimitive.content)
        assertEquals("NOT_ESTABLISHED", authority.getValue("invocationCount").jsonPrimitive.content)
        val owner =
            encoded.getValue("target").jsonObject.getValue("executionOwner").jsonObject.getValue("binding").jsonObject
        assertFalse("semantics" in owner)
        assertEquals("KOTLIN_2_4_21_ITERABLE_MAP_V1", owner.getValue("rule").jsonPrimitive.content)
        val steps =
            f.bounded<ImpactPathStepDocument>(
                listOf(
                    ImpactPathStepDocument.ReviewedLibrary(input),
                    ImpactPathStepDocument.ReviewedLibrary(f.transfer),
                )
            )
        assertEquals(
            ImpactPathClassificationDocument.Established(ImpactRelationshipDocument.DERIVED),
            steps.classifyImpact(),
        )
    }

    @Test
    fun `element suffix enters the exact callback parameter without a sibling swap`() {
        val f = ImpactReviewedValueFixture()
        val whole = input(f, ImpactReviewedValueSemanticsDocument.CONDITIONAL_INPUT_ELEMENT_TO_CALLBACK_PARAMETER)
        val input =
            whole.copy(
                source = whole.source.copy(valuePath = f.target.valuePath),
                target = whole.target.copy(valuePath = f.source.valuePath),
            )
        assertTrue(input.admitsEvidence())
        assertFalse(input.copy(target = input.target.copy(valuePath = EmptyImpactValuePath)).admitsEvidence())
        assertFalse(input.copy(source = input.source.copy(valuePath = f.source.valuePath)).admitsEvidence())
        assertEquals(
            ImpactPathClassificationDocument.Established(ImpactRelationshipDocument.EXACT),
            f.bounded<ImpactPathStepDocument>(listOf(ImpactPathStepDocument.ReviewedLibrary(input))).classifyImpact(),
        )
    }

    private fun input(
        f: ImpactReviewedValueFixture,
        semantics: ImpactReviewedValueSemanticsDocument,
    ): ImpactReviewedValueTransferDocument {
        val owner = ImpactExecutionOwnerDocument.ReviewedCallback(f.binding)
        val parameter =
            ImpactFormalParameterDocument(
                f.binding.enclosing,
                (ProtocolOffset.parse(0) as Refinement.Refined).value,
                f.text("item"),
                f.range(40, 45),
                owner,
            )
        val source =
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
        val target =
            f.source.copy(
                range = parameter.range,
                role = ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Parameter(parameter)),
                executionOwner = owner,
                valuePath = EmptyImpactValuePath,
            )
        return ImpactReviewedValueTransferDocument(
            source,
            target,
            ImpactReviewedValueRelationshipDocument.fromBinding(f.binding, semantics),
        )
    }
}
