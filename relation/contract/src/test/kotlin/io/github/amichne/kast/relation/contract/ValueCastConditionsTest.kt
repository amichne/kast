package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValueCastConditionsTest {
    @Test
    fun `both cast kinds preserve earlier facts through a parenthesized operand and local binding`() {
        for (kind in ValueCastKind.entries) {
            for (role in listOf(ValueRole.ExpressionResult, ValueRole.LocalRead)) {
                val operand = operand(role)
                val prior = cast(operand, useRange = flowRange(9, 45))
                val source =
                    operand.withState(conditions = operand.conditions.requiring(prior).flowRefined()).flowRefined()
                val fact = cast(source, kind)
                val result = result(source, fact)
                val transfer = ValueTransfer.fromCompiler(source, result, ValueTransferKind.CAST_RESULT).flowRefined()
                val binding =
                    ValueSite.fromCompiler(
                            source.enclosing,
                            flowRange(5, 50),
                            ValueRole.LocalBinding,
                            conditions = result.conditions,
                        )
                        .flowRefined()
                val local =
                    ValueTransfer.fromCompiler(transfer.target, binding, ValueTransferKind.LOCAL_BINDING).flowRefined()
                assertEquals(listOf(prior, fact), local.target.conditions.facts)
                assertSame(result.conditions, local.target.withState().flowRefined().conditions)
                assertEquals(listOf(prior), source.conditions.facts)
                assertTrue(fact.origin.matches(source))
                assertEquals(source.range, fact.origin.range)
                assertEquals(kind, fact.kind)
            }
        }
    }

    @Test
    fun `cast and subsequent binding reject removed or reordered prerequisites`() {
        val operand = operand()
        val prior = cast(operand, useRange = flowRange(9, 45))
        val source = operand.withState(conditions = operand.conditions.requiring(prior).flowRefined()).flowRefined()
        val fact = cast(source)
        val result = result(source, fact)
        val onlyCast = ValueConditions.Unconditional.requiring(fact).flowRefined()
        for (changed in listOf(onlyCast, onlyCast.requiring(prior).flowRefined())) {
            val lostPrefix =
                ValueSite.fromCompiler(
                        source.enclosing,
                        result.range,
                        result.role,
                        conditions = changed,
                    )
                    .flowRefined()
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(source, lostPrefix, ValueTransferKind.CAST_RESULT),
            )
        }
        val erased = ValueSite.fromCompiler(source.enclosing, flowRange(5, 50), ValueRole.LocalBinding).flowRefined()
        val unqualified = ValueSite.fromCompiler(source.enclosing, result.range, result.role).flowRefined()
        assertNotEquals(result.identity, unqualified.identity)
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(result, erased, ValueTransferKind.LOCAL_BINDING),
        )
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.CONDITIONS_NOT_PRESERVED),
            result.withState(conditions = source.conditions),
        )
    }

    @Test
    fun `cast range must contain the entire operand and remain inside execution`() {
        val source = operand()
        for (range in listOf(flowRange(11, 40), flowRange(5, 19), flowRange(30, 40))) {
            assertEquals(
                Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_OWNER),
                ValueCondition.CastSuccess.fromCompiler(
                    source,
                    range,
                    ValueCastKind.UNSAFE,
                    type("Any"),
                    type("String"),
                ),
            )
        }
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_EXECUTION_OWNER),
            ValueCondition.CastSuccess.fromCompiler(
                source,
                flowRange(5, 201),
                ValueCastKind.SAFE,
                type("Any"),
                type("String"),
            ),
        )
    }

    @Test
    fun `cast result requires exactly one new matching prerequisite and rejects other transfer kinds`() {
        val source = operand()
        val fact = cast(source)
        val target = result(source, fact)
        val missing = ValueSite.fromCompiler(source.enclosing, target.range, target.role).flowRefined()
        val alreadyQualified = source.withState(conditions = target.conditions).flowRefined()
        val extra =
            target
                .withState(conditions = target.conditions.requiring(cast(source, ValueCastKind.SAFE)).flowRefined())
                .flowRefined()
        for ((from, to) in listOf(source to missing, alreadyQualified to target, source to extra)) {
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(from, to, ValueTransferKind.CAST_RESULT),
            )
            assertFalse(ValueTransferEvidence.Direct.admits(from, to, ValueTransferKind.CAST_RESULT))
        }
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(source, target, ValueTransferKind.BRANCH_ALTERNATIVE),
        )
        val receiver =
            ValueSite.fromCompiler(
                    source.enclosing,
                    source.range,
                    ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)),
                )
                .flowRefined()
        val receiverFact =
            ValueCondition.StableReceiver.fromCompiler(
                    receiver,
                    target.range,
                    ValueReceiverKind.EXTENSION,
                    type("Any"),
                    type("String"),
                )
                .flowRefined()
        val wrongKind =
            missing.withState(conditions = source.conditions.requiring(receiverFact).flowRefined()).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(receiver, wrongKind, ValueTransferKind.CAST_RESULT),
        )
    }

    @Test
    fun `cast prerequisite cannot borrow another origin or a different use range`() {
        val source = operand()
        val field = flowField("Payload.value", 300, 320)
        val call = ValueInvocation.fromCompiler(source.enclosing, flowRange(60, 80), source.enclosing).flowRefined()
        val frame = ValueCallFrame.direct(call, source.executionOwner).flowRefined()
        val otherOrigins =
            listOf(
                ValueSite.fromCompiler(source.enclosing, flowRange(11, 20), source.role).flowRefined(),
                ValueSite.fromCompiler(source.enclosing, source.range, ValueRole.LocalRead).flowRefined(),
                ValueSite.fromCompiler(flowEndpoint("other", 0, 200), source.range, source.role).flowRefined(),
                source.withState(valuePath = flowPath(ValuePathSegment.Field(field))).flowRefined(),
                source.withState(callStack = ValueCallStack.Empty.enter(frame).flowRefined()).flowRefined(),
            )
        for (origin in otherOrigins) {
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(source, result(source, cast(origin)), ValueTransferKind.CAST_RESULT),
            )
        }
        val wrongUse = result(source, cast(source, useRange = flowRange(5, 45)))
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(source, wrongUse, ValueTransferKind.CAST_RESULT),
        )
    }

    @Test
    fun `cast result preserves owner path stack and compatible roles`() {
        val source = operand()
        val target = result(source, cast(source))
        val call = ValueInvocation.fromCompiler(source.enclosing, flowRange(60, 80), source.enclosing).flowRefined()
        val frame = ValueCallFrame.direct(call, source.executionOwner).flowRefined()
        val incompatibleTargets =
            listOf(
                target.withState(valuePath = flowPath(ValuePathSegment.Element)).flowRefined(),
                target.withState(callStack = ValueCallStack.Empty.enter(frame).flowRefined()).flowRefined(),
                ValueSite.fromCompiler(
                        flowEndpoint("other", 0, 200),
                        target.range,
                        target.role,
                        conditions = target.conditions,
                    )
                    .flowRefined(),
            )
        for (incompatible in incompatibleTargets) {
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(source, incompatible, ValueTransferKind.CAST_RESULT),
            )
        }
        val binding = operand(ValueRole.LocalBinding)
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.ROLE_MISMATCH),
            ValueTransfer.fromCompiler(binding, result(binding, cast(binding)), ValueTransferKind.CAST_RESULT),
        )
        val wrongRole =
            ValueSite.fromCompiler(source.enclosing, target.range, ValueRole.LocalRead, conditions = target.conditions)
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.ROLE_MISMATCH),
            ValueTransfer.fromCompiler(source, wrongRole, ValueTransferKind.CAST_RESULT),
        )
    }

    @Test
    fun `cast conditions retain kind types dependencies and basis in detached state`() {
        val source = operand()
        val unsafe = cast(source)
        val safe = cast(source, ValueCastKind.SAFE)
        val longer = cast(source, targetType = "fixture." + "X".repeat(1000))
        val original = cast(source, originalType = "kotlin.List<kotlin.Any>")
        val states = listOf(unsafe, safe, longer, original).map { result(source, it) }
        assertEquals(4, states.map { it.identity }.distinct().size)
        assertEquals(4, states.map { buildString { site(it) } }.distinct().size)
        assertEquals(
            2L * (longer.targetType.value.length - unsafe.targetType.value.length),
            states[2].retainedBytes - states[0].retainedBytes,
        )
        val foreign =
            ValueSite.fromCompiler(flowEndpoint("cast", 0, 200, generation = 2), source.range, source.role)
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH),
            foreign.withState(conditions = states[0].conditions),
        )
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH),
            states[0].conditions.requiring(cast(foreign)),
        )
        val field = flowField("Payload.value", 300, 320)
        val pathOrigin = source.withState(valuePath = flowPath(ValuePathSegment.Field(field))).flowRefined()
        assertTrue(field.declaration in result(source, cast(pathOrigin)).stateEndpoints())
    }

    private fun operand(role: ValueRole = ValueRole.ExpressionResult): ValueSite =
        ValueSite.fromCompiler(flowEndpoint("cast", 0, 200), flowRange(10, 20), role).flowRefined()

    private fun type(value: String): CanonicalCompilerType = CanonicalCompilerType.fromCompiler(value).flowRefined()

    private fun cast(
        source: ValueSite,
        kind: ValueCastKind = ValueCastKind.UNSAFE,
        useRange: ExactDeclarationTextRange = flowRange(5, 40),
        originalType: String = "kotlin.Any",
        targetType: String = "kotlin.String",
    ): ValueCondition.CastSuccess =
        ValueCondition.CastSuccess.fromCompiler(source, useRange, kind, type(originalType), type(targetType))
            .flowRefined()

    private fun result(source: ValueSite, fact: ValueCondition.CastSuccess): ValueSite =
        ValueSite.fromCompiler(
                source.enclosing,
                flowRange(5, 40),
                ValueRole.ExpressionResult,
                conditions = source.conditions.requiring(fact).flowRefined(),
            )
            .flowRefined()
}
