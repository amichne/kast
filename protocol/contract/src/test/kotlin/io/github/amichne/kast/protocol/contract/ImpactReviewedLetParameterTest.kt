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

/** The reviewed `let` receiver enters its callback's sole formal, preserving the receiver's established path. */
class ImpactReviewedLetParameterTest {
    @Test
    fun `let receiver enters formal zero with its exact path and an exact relationship`() {
        val f = ImpactReviewedScopeFixture()
        val input = input(f)
        assertTrue(input.admitsEvidence())
        val encoded = Json.parseToJsonElement(Json.encodeToString(input)).jsonObject
        val relation = encoded.getValue("binding").jsonObject.getValue("relation").jsonObject
        assertEquals("CONDITIONAL_RECEIVER_TO_CALLBACK_PARAMETER", relation.getValue("semantics").jsonPrimitive.content)
        val authority = encoded.getValue("binding").jsonObject.getValue("authority").jsonObject
        assertEquals("KOTLIN_2_3_0_LET_V1", authority.getValue("rule").jsonPrimitive.content)
        assertEquals("NOT_ESTABLISHED", authority.getValue("actualExecution").jsonPrimitive.content)
        assertEquals(
            ImpactPathClassificationDocument.Established(ImpactRelationshipDocument.EXACT),
            f.base
                .bounded<ImpactPathStepDocument>(listOf(ImpactPathStepDocument.ReviewedLibrary(input)))
                .classifyImpact(),
        )
    }

    @Test
    fun `let parameter rejects a changed path wrong owners a later formal and a collection rule`() {
        val f = ImpactReviewedScopeFixture()
        val input = input(f)
        assertFalse(input.copy(target = input.target.copy(valuePath = EmptyImpactValuePath)).admitsEvidence())
        assertFalse(
            input
                .copy(
                    source =
                        input.source.copy(executionOwner = ImpactExecutionOwnerDocument.DeclaredCallback(f.declared))
                )
                .admitsEvidence()
        )
        val foreign =
            ImpactExecutionOwnerDocument.ReviewedCallback(
                f.binding.copy(body = f.binding.body.copy(range = f.range(41, 79)))
            )
        assertFalse(input.copy(target = input.target.copy(executionOwner = foreign)).admitsEvidence())
        assertFalse(input.copy(target = target(f, index = 1)).admitsEvidence())
        assertFalse(input.copy(source = input.source.copy(range = f.range(45, 50))).admitsEvidence())
        val map = ImpactReviewedValueFixture()
        assertFalse(
            input
                .copy(
                    binding =
                        ImpactReviewedValueRelationshipDocument.fromBinding(
                            map.binding,
                            ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_TO_CALLBACK_PARAMETER,
                        )
                )
                .admitsEvidence()
        )
    }

    @Test
    fun `non-null receiver condition binds one use and survives the let transfer`() {
        val f = ImpactReviewedScopeFixture()
        val edge = input(f)
        val source = edge.source.copy(role = ImpactValueRoleDocument.ExpressionResult)
        val origin =
            ImpactValueConditionOriginDocument(
                ImpactExecutionReferenceDocument(source.enclosing, source.executionOwner),
                source.range,
                source.role,
                source.valuePath,
                source.callStack,
            )
        val fact = ImpactValueConditionDocument.ReceiverNonNull(origin, source.range)
        val encoded = Json.parseToJsonElement(Json.encodeToString<ImpactValueConditionDocument>(fact)).jsonObject
        assertEquals(setOf("type", "origin", "useRange"), encoded.keys)
        assertEquals("RECEIVER_NON_NULL", encoded.getValue("type").jsonPrimitive.content)
        val conditions = f.base.bounded<ImpactValueConditionDocument>(listOf(fact))
        val receiver = edge.source.copy(conditions = conditions)
        assertTrue(source.conservesConditions(receiver, ImpactTransferKindDocument.RECEIVER))
        assertFalse(source.conservesConditions(receiver, ImpactTransferKindDocument.RETURN))
        assertTrue(edge.copy(source = receiver, target = edge.target.copy(conditions = conditions)).admitsEvidence())
        assertFalse(edge.copy(source = receiver).admitsEvidence())
    }

    private fun input(f: ImpactReviewedScopeFixture): ImpactReviewedValueTransferDocument {
        val source =
            f.source.copy(
                range = f.range(32, 36),
                role =
                    ImpactValueRoleDocument.Receiver(
                        ImpactInvocationReferenceDocument(f.binding.supplierRange, f.binding.selected.declaration),
                        ImpactReceiverKindDocument.EXTENSION,
                    ),
                executionOwner = ImpactExecutionOwnerDocument.Declaration,
            )
        return ImpactReviewedValueTransferDocument(
            source,
            target(f),
            ImpactReviewedValueRelationshipDocument.fromBinding(
                f.binding,
                ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_TO_CALLBACK_PARAMETER,
            ),
        )
    }

    private fun target(f: ImpactReviewedScopeFixture, index: Int = 0): ImpactValueSiteReferenceDocument {
        val owner = ImpactExecutionOwnerDocument.ReviewedCallback(f.binding)
        val parameter =
            ImpactFormalParameterDocument(
                f.binding.enclosing,
                (ProtocolOffset.parse(index) as Refinement.Refined).value,
                f.base.text("id"),
                f.range(42, 44),
                owner,
            )
        return f.source.copy(
            range = parameter.range,
            role = ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Parameter(parameter)),
            executionOwner = owner,
        )
    }
}
