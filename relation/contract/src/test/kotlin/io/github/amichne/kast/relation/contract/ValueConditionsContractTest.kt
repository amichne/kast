package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValueConditionsContractTest {
    @Test
    fun `field read appends the exact receiver fact and later transfers conserve it`() {
        val source = receiver()
        val condition = condition(source)
        val conditions = source.conditions.requiring(condition).flowRefined()
        val read =
            ValueSite.fromCompiler(
                    source.enclosing,
                    condition.useRange,
                    ValueRole.ExpressionResult,
                    conditions = conditions,
                )
                .flowRefined()
        val field = flowField("Selected.payload", 300, 320)
        val transfer =
            ValueTransfer.fromCompiler(
                    source = source,
                    target = read,
                    kind = ValueTransferKind.FIELD_READ,
                    evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.ReadField(field)),
                )
                .flowRefined()
        val binding =
            ValueSite.fromCompiler(
                    source.enclosing,
                    flowRange(25, 45),
                    ValueRole.LocalBinding,
                    conditions = conditions,
                )
                .flowRefined()
        val local = ValueTransfer.fromCompiler(transfer.target, binding, ValueTransferKind.LOCAL_BINDING).flowRefined()
        assertSame(conditions, local.target.withState().flowRefined().conditions)
        assertEquals(listOf(condition), local.target.conditions.facts)
        val erased = ValueSite.fromCompiler(source.enclosing, binding.range, binding.role).flowRefined()
        assertNotEquals(binding.identity, erased.identity)
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(read, erased, ValueTransferKind.LOCAL_BINDING),
        )
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(erased, binding, ValueTransferKind.LOCAL_READ),
        )
    }

    @Test
    fun `conditions are ordered immutable and idempotent while erasure and reordering reject`() {
        val source = receiver()
        val first = condition(source)
        val second = condition(source, refinedType = "fixture.Other")
        val one = ValueConditions.Unconditional.requiring(first).flowRefined()
        val two = one.requiring(second).flowRefined()
        assertEquals(listOf(first), one.facts)
        assertEquals(listOf(first, second), two.facts)
        assertSame(two, two.requiring(first).flowRefined())
        assertThrows(UnsupportedOperationException::class.java) { (two.facts as MutableList<ValueCondition>).clear() }
        val site = source.withState(conditions = two).flowRefined()
        val reversed = ValueConditions.Unconditional.requiring(second).flowRefined().requiring(first).flowRefined()
        for (replacement in listOf(ValueConditions.Unconditional, one, reversed)) {
            assertEquals(
                Refinement.Rejected(ValueSiteFailure.CONDITIONS_NOT_PRESERVED),
                site.withState(conditions = replacement),
            )
        }
    }

    @Test
    fun `field read cannot borrow a condition from a different origin or use`() {
        val source = receiver()
        val field = flowField("Selected.payload", 300, 320)
        val evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.ReadField(field))
        val otherRange = ValueSite.fromCompiler(source.enclosing, flowRange(1, 200), source.role).flowRefined()
        val otherPath = source.withState(valuePath = flowPath(ValuePathSegment.Field(field))).flowRefined()
        for (origin in listOf(otherRange, otherPath)) {
            val conditions = ValueConditions.Unconditional.requiring(condition(origin)).flowRefined()
            val target =
                ValueSite.fromCompiler(
                        source.enclosing,
                        flowRange(30, 45),
                        ValueRole.ExpressionResult,
                        conditions = conditions,
                    )
                    .flowRefined()
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(source, target, ValueTransferKind.FIELD_READ, evidence),
            )
        }
        val conditions = ValueConditions.Unconditional.requiring(condition(source)).flowRefined()
        val wrongUse =
            ValueSite.fromCompiler(
                    source.enclosing,
                    flowRange(50, 60),
                    ValueRole.ExpressionResult,
                    conditions = conditions,
                )
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(source, wrongUse, ValueTransferKind.FIELD_READ, evidence),
        )
    }

    @Test
    fun `receiver kind and foreign condition basis reject precisely`() {
        val source = receiver()
        val condition = condition(source)
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH),
            ValueCondition.StableReceiver.fromCompiler(
                source = source,
                useRange = condition.useRange,
                kind = ValueReceiverKind.DISPATCH,
                originalType = condition.originalType,
                refinedType = condition.refinedType,
            ),
        )
        val conditions = ValueConditions.Unconditional.requiring(condition).flowRefined()
        val foreign = receiver(generation = 2)
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH),
            foreign.withState(conditions = conditions),
        )
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH),
            conditions.requiring(condition(foreign)),
        )
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH),
            ValueSite.fromCompiler(foreign.enclosing, source.range, source.role, conditions = conditions),
        )
    }

    @Test
    fun `canonical state distinguishes condition payload and exact origin while storage charges text`() {
        val source = receiver()
        val condition = condition(source)
        val qualified =
            source
                .withState(conditions = ValueConditions.Unconditional.requiring(condition).flowRefined())
                .flowRefined()
        val longer =
            source
                .withState(
                    conditions =
                        ValueConditions.Unconditional.requiring(condition(source, "fixture." + "X".repeat(1000)))
                            .flowRefined()
                )
                .flowRefined()
        assertNotEquals(buildString { site(source) }, buildString { site(qualified) })
        assertNotEquals(buildString { site(qualified) }, buildString { site(longer) })
        assertTrue(qualified.retainedBytes > source.retainedBytes)
        assertEquals(
            2L * (1008 - condition.refinedType.value.length),
            longer.retainedBytes - qualified.retainedBytes,
        )
        assertEquals(source.executionOwner, condition.origin.execution)
        assertEquals(source.range, condition.origin.range)
        assertEquals(source.role, condition.origin.role)
        assertEquals(source.valuePath, condition.origin.valuePath)
        assertEquals(source.callStack, condition.origin.callStack)
    }

    @Test
    fun `canonical conditions retain origin range path and call stack independently of arrival state`() {
        val source = receiver()
        val field = flowField("Selected.payload", 300, 320)
        val call = ValueInvocation.fromCompiler(source.enclosing, flowRange(60, 80), source.enclosing).flowRefined()
        val frame = ValueCallFrame.direct(call, source.executionOwner).flowRefined()
        val origins =
            listOf(
                source,
                ValueSite.fromCompiler(source.enclosing, flowRange(1, 200), source.role).flowRefined(),
                source.withState(valuePath = flowPath(ValuePathSegment.Field(field))).flowRefined(),
                source.withState(callStack = ValueCallStack.Empty.enter(frame).flowRefined()).flowRefined(),
            )
        val arrivals = origins.map { origin ->
            source
                .withState(conditions = ValueConditions.Unconditional.requiring(condition(origin)).flowRefined())
                .flowRefined()
        }
        assertEquals(origins.size, arrivals.map { it.identity }.distinct().size)
        assertEquals(origins.size, arrivals.map { buildString { site(it) } }.distinct().size)
    }

    private fun receiver(generation: Long = 1): ValueSite {
        val owner = flowEndpoint("refinedReceiver", 0, 200, generation = generation)
        return ValueSite.fromCompiler(
                owner,
                owner.range,
                ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)),
            )
            .flowRefined()
    }

    private fun condition(source: ValueSite, refinedType: String = "fixture.Selected"): ValueCondition.StableReceiver =
        ValueCondition.StableReceiver.fromCompiler(
                source = source,
                useRange = flowRange(30, 45),
                kind = ValueReceiverKind.EXTENSION,
                originalType = CanonicalCompilerType.fromCompiler("fixture.Choice").flowRefined(),
                refinedType = CanonicalCompilerType.fromCompiler(refinedType).flowRefined(),
            )
            .flowRefined()
}
