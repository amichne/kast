package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.SymbolSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueFlowPhysicalStorageTest {
    @Test
    fun `a new target keeps its header and references to four shared payloads`() {
        val f = PhysicalStorageFixture()
        val target = f.target(f.source)
        assertNotSame(f.source, target)
        assertSame(f.source.enclosing, target.enclosing)
        assertSame(f.source.valuePath, target.valuePath)
        assertSame(f.source.callStack, target.callStack)
        assertSame(f.source.predicates, target.predicates)

        assertEquals(2048L + 2048L + 4L * 8L, f.bytes(f.source, target) - f.baseBytes())
        val refusal = ValueFlowObligation(f.source, ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED)
        assertEquals(2048L, f.bytes(f.source, target, obligations = listOf(refusal)) - f.bytes(f.source, target))
    }

    @Test
    fun `a detached equal source keeps its distinct header with shared payload references`() {
        val f = PhysicalStorageFixture()
        val detached = ValueSite.fromCompiler(f.owner, f.source.range, f.source.role).flowRefined()
        val target = f.target(f.source)
        assertEquals(f.source, detached)
        assertNotSame(f.source, detached)

        assertEquals(2048L + 4L * 8L, f.bytes(f.source, target, detached) - f.bytes(f.source, target))
    }

    @Test
    fun `equal distinct endpoints keep the full endpoint allowance`() {
        val f = PhysicalStorageFixture()
        val detached = RelationEndpoint.resolve(f.owner.lease, f.owner.scope, f.owner.evidence).flowRefined()
        assertEquals(f.owner, detached)
        assertNotSame(f.owner, detached)
        val shared = f.bytes(f.source, f.target(f.source))
        val separate = f.bytes(f.source, f.target(f.source, enclosing = detached))

        assertEquals(2048L + 2L * detached.detachedTextUnits() - 8L, separate - shared)
    }

    @Test
    fun `equal distinct path segments keep their records while shared segments cost a reference`() {
        val f = PhysicalStorageFixture()
        val field = flowField("stored", 300, 320)
        val segment = ValuePathSegment.Field(field)
        val source = f.source.withState(valuePath = f.path(segment)).flowRefined()
        val sharedPath = f.path(segment)
        val distinctPath = f.path(ValuePathSegment.Field(field))
        assertEquals(sharedPath, distinctPath)
        assertNotSame(sharedPath, distinctPath)
        assertNotSame(sharedPath.segments.single(), distinctPath.segments.single())
        val shared = f.bytes(source, f.target(source, path = sharedPath))
        val separate = f.bytes(source, f.target(source, path = distinctPath))

        assertEquals(2048L - 8L, separate - shared)
    }

    @Test
    fun `equal distinct direct frames keep their records while shared frames cost a reference`() {
        val f = PhysicalStorageFixture()
        val call = ValueInvocation.fromCompiler(f.owner, flowRange(50, 70), f.owner).flowRefined()
        val frame = ValueCallFrame.direct(call, f.owner.valueExecutionOwner).flowRefined()
        val detached = ValueCallFrame.direct(call, f.owner.valueExecutionOwner).flowRefined()
        val source = f.source.withState(callStack = f.stack(frame)).flowRefined()
        assertEquals(frame, detached)
        assertNotSame(frame, detached)
        val shared = f.bytes(source, f.target(source, stack = f.stack(frame)))
        val separate = f.bytes(source, f.target(source, stack = f.stack(detached)))

        assertEquals(4096L - 8L, separate - shared)
    }

    @Test
    fun `equal distinct predicates keep their record and reference to the exact tested value`() {
        val f = PhysicalStorageFixture()
        val subject = ValuePredicateSubject.fromCompiler(f.source)
        val predicate = ValuePredicate.NonNull.fromCompiler(subject)
        val detached = ValuePredicate.NonNull.fromCompiler(subject)
        val source = f.source.withState(predicates = f.predicates(predicate)).flowRefined()
        assertEquals(predicate, detached)
        assertNotSame(predicate, detached)
        assertSame(predicate.subject, detached.subject)
        val shared = f.bytes(source, f.target(source, predicates = f.predicates(predicate)))
        val separate = f.bytes(source, f.target(source, predicates = f.predicates(detached)))

        assertEquals(2048L + 8L - 8L, separate - shared)
    }
}

private class PhysicalStorageFixture {
    val owner = flowEndpoint("investigate", 0, 200) as RelationEndpoint.Resolved
    val source = ValueSite.fromCompiler(owner, flowRange(20, 35), ValueRole.LocalRead).flowRefined()
    private val targetRange = flowRange(10, 180)
    private val domain =
        RelationRequest.start(
            SymbolSelector.issue(owner.lease, owner.scope, owner.evidence),
            RelationMeaning.References,
            RelationBudget(
                ResourceBudget(
                    ResultLimit.parse(32).flowRefined(),
                    WorkUnitLimit.parse(128).flowRefined(),
                    ElapsedTimeLimitMillis.parse(1000).flowRefined(),
                ),
                RelationByteLimit.parse(100_000).flowRefined(),
            ),
            RelationSearchBoundary.WORKSPACE_EXPANSION,
        )

    fun target(
        source: ValueSite,
        enclosing: RelationEndpoint = owner,
        path: ValueAccessPath = source.valuePath,
        stack: ValueCallStack = source.callStack,
        predicates: ValuePredicates = source.predicates,
    ): ValueSite =
        ValueSite.fromCompiler(
                enclosing,
                targetRange,
                ValueRole.Return,
                valuePath = path,
                callStack = stack,
                predicates = predicates,
            )
            .flowRefined()

    fun bytes(
        source: ValueSite,
        target: ValueSite,
        edgeSource: ValueSite = source,
        obligations: List<ValueFlowObligation> = emptyList(),
    ): Long {
        val edge = ValueTransfer.fromCompiler(edgeSource, target, ValueTransferKind.RETURN).flowRefined()
        return ValueFlowStep.detachedByteCount(source, domain, listOf(edge), obligations).value
    }

    fun baseBytes() = ValueFlowStep.detachedByteCount(source, domain, emptyList(), emptyList()).value

    fun path(segment: ValuePathSegment) = ValueAccessPath.fromSegments(listOf(segment)).flowRefined()

    fun stack(frame: ValueCallFrame) = ValueCallStack.Empty.enter(frame).flowRefined()

    fun predicates(predicate: ValuePredicate) = ValuePredicates.fromCompiler(listOf(predicate)).flowRefined()
}
