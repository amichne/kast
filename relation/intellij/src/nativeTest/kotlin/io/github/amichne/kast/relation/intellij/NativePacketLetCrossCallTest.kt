package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Two genuine source calls establish the control. Only the retained top frame changes before fresh readmission. */
class NativePacketLetCrossCallTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-let-cross-call-jdk"

    fun testFirstGenuineReturnCannotUseTheSecondActualLetFrame() {
        val fixture = packetFixture(listOf(NativePacketLetCallbackSource.CONTROL))
        val source = NativePacketLetCallbackSource(project, getOrCreateProjectBaseDir(), fixture)
        val first = source.control("twoCalls", index = 0)
        val second = source.control("twoCalls", index = 1)
        assertEquals(first.binding.invocation.callable.valueIdentity, second.binding.invocation.callable.valueIdentity)
        assertFalse(first.binding.invocation.range == second.binding.invocation.range)
        assertFalse(first.formal.executionOwner == second.formal.executionOwner)
        val normal = source.normalReturn(first)
        assertEquals(ValueRole.Return, normal.role)
        assertEquals(first.formal.executionOwner, normal.executionOwner)
        assertEquals(first.binding.invocation, normal.callStack.frames.last().invocation)
        val wrong = wrongTopFrame(normal, second)
        assertRead(source, wrong, setOf(first.binding.invocation.range, second.binding.invocation.range))
    }

    private fun wrongTopFrame(normal: ValueSite, second: LetCallbackFacts): ValueSite {
        val secondFrame =
            ValueCallFrame.reviewedCallback(second.binding, second.argument, second.formal).nativeRefined()
        assertEquals(normal.callStack.frames.dropLast(1), second.receiver.callStack.frames)
        val wrong =
            normal.withState(callStack = second.receiver.callStack.enter(secondFrame).nativeRefined()).nativeRefined()
        assertUnchangedSource(normal, wrong)
        assertEquals(second.binding.invocation, wrong.callStack.frames.last().invocation)
        return wrong
    }

    private fun assertRead(
        source: NativePacketLetCallbackSource,
        wrong: ValueSite,
        calls: Set<ExactDeclarationTextRange>,
    ) {
        when (val result = source.readRaw(wrong)) {
            is ValueFlowRead.Rejected -> {
                assertEquals(ValueFlowRejection.STALE_SITE, result.cause)
                println("LET_CROSS_FRAME_RESULT status=REJECTED cause=${result.cause}")
            }
            is ValueFlowRead.Observed -> assertNoCallResults(result.step, wrong, calls)
            is ValueFlowRead.Suspended -> fail("A mismatched callback frame must not start a resumable source scan")
            is ValueFlowRead.ContractRejected ->
                fail("A wrong callback frame must not break the detached step contract")
        }
    }

    private fun assertNoCallResults(step: ValueFlowStep, wrong: ValueSite, calls: Set<ExactDeclarationTextRange>) {
        assertEquals(wrong, step.source)
        assertTrue("A mismatched actual callback frame acquired a modeled edge", step.applications.isEmpty())
        assertTrue(
            "A mismatched actual callback frame acquired either call result",
            step.transfers.none { transfer ->
                transfer.target.role == ValueRole.ExpressionResult && transfer.target.range in calls
            },
        )
        println("LET_CROSS_FRAME_RESULT status=NO_MODELED_OR_CALL_RESULT causes=${step.obligations.map { it.cause }}")
    }

    private fun assertUnchangedSource(first: ValueSite, wrong: ValueSite) {
        assertEquals(first.enclosing, wrong.enclosing)
        assertEquals(first.range, wrong.range)
        assertEquals(first.role, wrong.role)
        assertEquals(first.executionOwner, wrong.executionOwner)
        assertEquals(first.valuePath, wrong.valuePath)
        assertEquals(first.predicates, wrong.predicates)
        assertEquals(first.basis, wrong.basis)
        assertEquals(first.callStack.frames.size, wrong.callStack.frames.size)
        assertEquals(first.callStack.frames.dropLast(1), wrong.callStack.frames.dropLast(1))
    }
}
