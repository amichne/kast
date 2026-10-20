package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueCallTarget
import io.github.amichne.kast.relation.contract.ValueExecutionBody
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransferKind

/** The original source callback return must select its real builtin use and actual supplying source frame. */
class NativePacketSeedCallbackReturnTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-seed-callback-return-jdk"

    fun testOriginalS2AnonymousReturnHasItsExactK2SupplierAndActualBuiltinUse() {
        val route = route()
        val returned = route.returned()
        val facts = NativePacketSeedCallbackReturnFacts(project, getOrCreateProjectBaseDir()).read(returned)
        assertEquals(ValueCallStack.Empty, returned.callStack)
        assertTrue(returned.executionOwner.body is ValueExecutionBody.Anonymous)
        assertTrue(returned.executionOwner.hasSameProofAs(facts.body))
        assertTrue(returned.enclosing.hasSameProofAs(facts.caller))
        println("SEED_CALLBACK_RETURN_FACTS original_s2=ACTUAL body_and_supplier=ACTUAL_K2 transfer=NOT_RUN")
    }

    fun testOriginalS2EmptyStackReturnReachesOnlyTheActualBuiltinResultWithItsSupplierFrame() {
        val route = route()
        val returned = route.returned()
        val facts = NativePacketSeedCallbackReturnFacts(project, getOrCreateProjectBaseDir()).read(returned)
        assertEquals(ValueCallStack.Empty, returned.callStack)
        assertTrue(returned.executionOwner.hasSameProofAs(facts.body))
        val step = route.read(returned)
        val selected = step.transfers.filter { it.kind == ValueTransferKind.SOURCE_CALL_RETURN }
        assertEquals(
            "The original S2 anonymous Return1029:1057 needs its actual builtin result872:897. " +
                "Causes=${step.obligations.map { it.cause }}. Later sequential/getOffer return=NOT_REACHED",
            1,
            selected.size,
        )
        val transfer = selected.single()
        assertEquals(returned, transfer.source)
        assertEquals(facts.invoked, transfer.target.range)
        assertEquals(ValueRole.ExpressionResult, transfer.target.role)
        assertTrue(transfer.target.enclosing.hasSameProofAs(facts.supplier))
        assertTrue(transfer.target.executionOwner.hasSameProofAs(ValueExecutionOwner.declaration(facts.supplier)))
        assertEquals(returned.valuePath, transfer.target.valuePath)
        assertEquals(returned.predicates, transfer.target.predicates)
        assertEquals(1, transfer.target.callStack.frames.size)
        val frame = transfer.target.callStack.frames.single()
        assertEquals(ValueCallTarget.Direct, frame.target)
        assertTrue(frame.callee.hasSameProofAs(ValueExecutionOwner.declaration(facts.supplier)))
        val supplying = frame.invocation as ValueInvocation
        assertEquals(facts.supplierCall, supplying.range)
        assertTrue(supplying.callable.hasSameProofAs(facts.supplier))
        assertTrue(supplying.enclosing.hasSameProofAs(facts.caller))
        assertTrue(supplying.executionOwner.hasSameProofAs(ValueExecutionOwner.declaration(facts.caller)))
        assertTrue(step.applications.isEmpty())
        assertTrue(
            "The callback return must not skip the actual builtin and source supplier body",
            step.transfers.none { it.target.range == facts.supplierCall },
        )
        println("SEED_CALLBACK_RETURN_RESULT builtin_and_supplier_context=ASSERTED later_named_return=NOT_RUN")
    }

    private fun route(): NativePacketSeedCallbackReturnRoute {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        return NativePacketSeedCallbackReturnRoute(project, getOrCreateProjectBaseDir(), fixture)
    }
}
