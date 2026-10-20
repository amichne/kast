package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind

/** Supplemental source controls distinguish actual operands, normal null, abrupt calls and discarded results. */
class NativePacketElvisControlTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-elvis-control-jdk"

    fun testDirectNullableLeftReachesWholeElvisWithItsOwnWholeNonNullSubject() = normal(ElvisControl.LEFT)

    fun testDirectRightReachesWholeElvisWithoutAnInventedNullPredicate() = normal(ElvisControl.RIGHT)

    fun testNullableNothingOnTheRightRemainsANormalNullResult() = normal(ElvisControl.RIGHT_NULL)

    fun testNullableNothingOnTheLeftCannotContributeToEitherElvisResultOrFallback() {
        val (source, case) = case(ElvisControl.LEFT_NULL)
        val read = source.read(case.source)
        assertTrue(
            "A null-only left operand is not the selected value",
            read.transfers.none { transfer ->
                transfer.kind == ValueTransferKind.BRANCH_ALTERNATIVE ||
                    transfer.kind == ValueTransferKind.RETURN ||
                    transfer.target.range == case.right
            },
        )
        assertTrue(read.applications.isEmpty())
        println("ELVIS_CONTROL_RESULT case=LEFT_NULL claim=NO_SELECTED_PAYLOAD completion=NOT_ASSERTED")
    }

    fun testNonnullableNothingOnTheRightKeepsAbruptCompletion() {
        val (source, case) = case(ElvisControl.BOTTOM)
        val read = source.read(case.source)
        assertNoReturnOrBranch(read)
        assertEquals(listOf(ValueFlowUnsupportedCause.ABRUPT_COMPLETION), read.obligations.map { it.cause })
    }

    fun testUnitValuedElvisKeepsItsUnsupportedQualification() {
        val (source, case) = case(ElvisControl.UNIT)
        val read = source.read(case.source)
        assertNoReturnOrBranch(read)
        assertEquals(listOf(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION), read.obligations.map { it.cause })
    }

    fun testDiscardedElvisCannotAcquireTheLaterConstantReturn() {
        val (source, case) = case(ElvisControl.DISCARDED)
        val first = source.read(case.source)
        assertTrue(first.transfers.none { it.kind == ValueTransferKind.RETURN })
        val branches = first.transfers.filter { it.kind == ValueTransferKind.BRANCH_ALTERNATIVE }
        for (branch in branches) {
            assertEquals(case.branch, branch.target.range)
            val second = source.read(branch.target)
            assertTrue(second.transfers.none { it.kind == ValueTransferKind.RETURN })
        }
        println("ELVIS_CONTROL_RESULT case=DISCARDED claim=NO_FALSE_RETURN completion=NOT_ASSERTED")
    }

    fun testResultAcrossFinallyKeepsTheExactFinallyQualification() {
        val (source, case) = case(ElvisControl.FINALLY)
        val first = source.read(case.source)
        val branches = first.transfers.filter { it.kind == ValueTransferKind.BRANCH_ALTERNATIVE }
        val last = if (branches.isEmpty()) first else source.read(branches.single().target)
        assertTrue(first.transfers.none { it.kind == ValueTransferKind.RETURN })
        assertTrue(last.transfers.none { it.kind == ValueTransferKind.RETURN })
        assertEquals(listOf(ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED), last.obligations.map { it.cause })
    }

    fun testNestedArgumentKeepsItsRealSourceCallAndCannotJumpToTheElvis() {
        val (source, case) = case(ElvisControl.FOREIGN)
        val read = source.read(case.source)
        assertEquals(listOf(ValueTransferKind.ARGUMENT), read.transfers.map { it.kind })
        val argument = read.transfers.single().target.role as ValueRole.Argument
        assertEquals("wrapper", argument.call.callable.name.value)
        assertEquals(0, argument.position.value)
        assertTrue(
            read.transfers.none { transfer ->
                transfer.target.range == case.branch || transfer.target.range == case.right
            }
        )
        assertTrue(read.applications.isEmpty())
    }

    private fun normal(control: ElvisControl) {
        val (source, case) = case(control)
        val read = source.read(case.source)
        val branches = read.transfers.filter { it.kind == ValueTransferKind.BRANCH_ALTERNATIVE }
        assertEquals("An actual selected normal operand needs its whole Elvis result", 1, branches.size)
        val target = branches.single().target
        assertEquals(case.branch, target.range)
        assertEquals(ValueRole.ExpressionResult, target.role)
        assertEquals(case.source.enclosing, target.enclosing)
        assertEquals(case.source.executionOwner, target.executionOwner)
        assertEquals(case.source.callStack, target.callStack)
        assertEquals(case.source.valuePath, target.valuePath)
        if (control == ElvisControl.LEFT) assertLeftGuard(case.source, target)
        else assertEquals(case.source.predicates, target.predicates)
        val returned = source.read(target)
        assertEquals(listOf(ValueTransferKind.RETURN), returned.transfers.map { it.kind })
        assertEquals(target.predicates, returned.transfers.single().target.predicates)
        if (control == ElvisControl.LEFT) assertTrue(read.transfers.none { it.target.range == case.right })
    }

    private fun assertLeftGuard(source: ValueSite, target: ValueSite) {
        assertTrue(target.predicates.entries.containsAll(source.predicates.entries))
        val added = target.predicates.entries - source.predicates.entries.toSet()
        assertEquals(1, added.size)
        val guard = added.single() as ValuePredicate.NonNull
        val expected =
            source.withState(valuePath = ValueAccessPath.Whole, predicates = ValuePredicates.Empty).nativeRefined()
        assertEquals(expected, guard.subject.occurrence)
    }

    private fun assertNoReturnOrBranch(step: ValueFlowStep) {
        assertTrue(
            step.transfers.none { transfer ->
                transfer.kind == ValueTransferKind.BRANCH_ALTERNATIVE || transfer.kind == ValueTransferKind.RETURN
            }
        )
        assertTrue(step.applications.isEmpty())
    }

    private fun case(control: ElvisControl): Pair<NativePacketElvisControlSource, ElvisControlCase> {
        val fixture = packetFixture(listOf(NativePacketElvisControlSource.SOURCE))
        val source = NativePacketElvisControlSource(project, getOrCreateProjectBaseDir(), fixture)
        return source to source.control(control)
    }
}
