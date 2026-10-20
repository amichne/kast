package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Detached scanner capacity and progress laws. */
class LocalBindingPhysicalCapacityTest {
    @Test
    fun `shared local-read state fits a native-scale grant and keeps exact progress`() {
        val f = LocalCapacityFixture()
        val read = f.scan(grant = NATIVE_GRANT, work = 3L)
        val page = assertInstanceOf(ValueFlowRead.Suspended::class.java, read)

        assertEquals(ValueFlowSuspensionCause.WORK_LIMIT_REACHED, page.cause)
        assertEquals(listOf(0), f.confirmed)
        assertEquals(setOf(f.key(0)), page.remainder.consumed)
        assertEquals(setOf(f.targets[0].identity), page.remainder.emitted)
        assertSame(f.targets[0], page.step.transfers.single().target)
        assertSame(f.source.callStack, page.step.transfers.single().target.callStack)
        assertEquals(3L, page.step.examinedWorkUnits.value)
        assertEquals(1, page.step.receipts.single().returnedResults.value)
        assertTrue(page.step.receipts.single().returnedBytes.value <= NATIVE_GRANT)
    }

    @Test
    fun `an insufficient grant rejects before role admission or enumeration`() {
        val f = LocalCapacityFixture()
        val read = assertInstanceOf(ValueFlowRead.Rejected::class.java, f.scan(grant = 1L))

        assertEquals(ValueFlowRejection.GRANT_TOO_SMALL, read.cause)
        assertEquals(1L, read.examinedWorkUnits.value)
        assertEquals(0, f.roleAdmissions)
        assertEquals(0, f.enumerations)
        assertEquals(emptyList<Int>(), f.confirmed)
    }

    @Test
    fun `a detached equal stack pays its payload and retries without proof loss`() {
        val f = LocalCapacityFixture(detachedIndices = setOf(0), candidateCount = 1)
        val shared = f.sharedTarget(0)
        assertEquals(shared.identity, f.targets[0].identity)
        assertNotSame(shared.callStack, f.targets[0].callStack)
        assertEquals(12L * 4096L - 8L, f.targetBytes(f.targets[0]) - f.targetBytes(shared))

        val first = assertInstanceOf(ValueFlowRead.Rejected::class.java, f.scan(grant = NATIVE_GRANT))
        assertEquals(ValueFlowRejection.GRANT_TOO_SMALL, first.cause)
        assertEquals(3L, first.examinedWorkUnits.value)
        assertEquals(listOf(0), f.confirmed)
        val last = assertInstanceOf(ValueFlowRead.Observed::class.java, f.scan(grant = LARGE_GRANT))
        assertEquals(listOf(0, 0), f.confirmed)
        assertSame(f.targets[0], last.step.transfers.single().target)
        assertSame(f.targets[0].callStack, last.step.transfers.single().target.callStack)
        assertEquals(3L, last.step.examinedWorkUnits.value)
    }

    @Test
    fun `a refused candidate leaves its address unconsumed after earlier progress`() {
        val f = LocalCapacityFixture(detachedIndices = setOf(1))
        val first = assertInstanceOf(ValueFlowRead.Suspended::class.java, f.scan(grant = NATIVE_GRANT))

        assertEquals(ValueFlowSuspensionCause.BYTE_LIMIT_REACHED, first.cause)
        assertEquals(listOf(0, 1), f.confirmed)
        assertEquals(setOf(f.key(0)), first.remainder.consumed)
        assertEquals(setOf(f.targets[0].identity), first.remainder.emitted)
        assertEquals(listOf(f.targets[0]), first.step.transfers.map { it.target })
        assertEquals(4L, first.step.examinedWorkUnits.value)
        val last =
            assertInstanceOf(
                ValueFlowRead.Observed::class.java,
                f.scan(grant = LARGE_GRANT, remainder = first.remainder),
            )
        assertEquals(listOf(0, 1, 1), f.confirmed)
        assertSame(f.targets[1], last.step.transfers.single().target)
        assertEquals(setOf(f.key(0)), first.remainder.consumed)
        assertEquals(setOf(f.targets[0].identity), first.remainder.emitted)
    }

    private companion object {
        const val NATIVE_GRANT = 132_839L
        const val LARGE_GRANT = 1_000_000L
    }
}

private class LocalCapacityFixture(
    detachedIndices: Set<Int> = emptySet(),
    candidateCount: Int = 2,
) {
    private val owner = flowEndpoint("scan", 0, 1000)
    private val frames =
        (0 until 12).map { index ->
            val call =
                ValueInvocation.fromCompiler(owner, flowRange(100 + index * 10, 105 + index * 10), owner).flowRefined()
            ValueCallFrame.direct(call, owner.valueExecutionOwner).flowRefined()
        }
    private val stack = stack(frames)
    val source =
        ValueSite.fromCompiler(owner, flowRange(20, 30), ValueRole.LocalBinding, callStack = stack).flowRefined()
    val targets =
        (0 until candidateCount).map { index ->
            val selectedStack =
                if (index in detachedIndices)
                    stack(
                        frames.map { ValueCallFrame.direct(it.invocation as ValueInvocation, it.callee).flowRefined() }
                    )
                else stack
            target(index, selectedStack)
        }
    val confirmed = mutableListOf<Int>()
    var roleAdmissions = 0
    var enumerations = 0

    fun key(index: Int): LocalReferenceKey {
        val range = flowRange(500 + index * 10, 505 + index * 10)
        return LocalReferenceKey(range, range, LocalReferenceKind.fromBoundary("fixture.Reference").flowRefined())
    }

    fun sharedTarget(index: Int): ValueSite = target(index, stack)

    private fun target(index: Int, callStack: ValueCallStack): ValueSite =
        ValueSite.fromCompiler(
                enclosing = source.enclosing,
                range = flowRange(500 + index * 10, 505 + index * 10),
                role = ValueRole.LocalRead,
                executionOwner = source.executionOwner,
                valuePath = source.valuePath,
                callStack = callStack,
                predicates = source.predicates,
            )
            .flowRefined()

    fun targetBytes(target: ValueSite): Long {
        val grant = budget(1_000_000L, 32L)
        val domain =
            RelationRequest.start(owner, RelationMeaning.References, grant, RelationSearchBoundary.RETAINED_SUBJECT)
        val edge = ValueTransfer.fromCompiler(source, target, ValueTransferKind.LOCAL_READ).flowRefined()
        return ValueFlowStep.detachedByteCount(source, domain, listOf(edge), emptyList()).value
    }

    fun scan(
        grant: Long,
        work: Long = 32L,
        remainder: LocalBindingReadRemainder? = null,
    ): ValueFlowRead {
        val budget = budget(grant, work)
        val request = ValueFlowRequest(source, budget, RelationSearchBoundary.RETAINED_SUBJECT, remainder)
        val domain = RelationRequest.start(owner, RelationMeaning.References, budget, request.boundary)
        return LocalBindingReferenceScan<Int>(
                request = request,
                domain = domain,
                enumerate = { traversal ->
                    enumerations++
                    for (index in targets.indices) if (!traversal(index)) break
                },
                key = ::key,
                confirm = { index ->
                    confirmed += index
                    LocalReferenceResolution.Transfer(targets[index])
                },
                checkCanceled = {},
                elapsedMillis = { 0L },
                admitRole = {
                    roleAdmissions++
                    Refinement.Refined(Unit)
                },
            )
            .read()
    }

    private fun stack(frames: List<ValueCallFrame>): ValueCallStack =
        frames.fold(ValueCallStack.Empty) { current, frame -> current.enter(frame).flowRefined() }

    private fun budget(bytes: Long, work: Long): RelationBudget =
        RelationBudget(
            ResourceBudget(
                ResultLimit.parse(32).flowRefined(),
                WorkUnitLimit.parse(work).flowRefined(),
                ElapsedTimeLimitMillis.parse(1000L).flowRefined(),
            ),
            RelationByteLimit.parse(bytes).flowRefined(),
        )
}
