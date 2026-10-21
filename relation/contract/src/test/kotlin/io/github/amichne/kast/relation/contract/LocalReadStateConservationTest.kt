package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalReadStateConservationTest {
    @Test
    fun `a resumed reference scan preserves its exact field and matched caller`() {
        val owner = flowEndpoint("owner", 0, 200)
        val caller = flowEndpoint("caller", 400, 600)
        val invocation = ValueInvocation.fromCompiler(caller, flowRange(420, 440), owner).flowRefined()
        val frame = ValueCallFrame.direct(invocation, ValueExecutionOwner.declaration(owner)).flowRefined()
        val stack = ValueCallStack.Empty.enter(frame).flowRefined()
        val path = flowPath(ValuePathSegment.Field(flowField("Request.destination", 300, 320)))
        val source =
            ValueSite.fromCompiler(
                    owner,
                    flowRange(10, 20),
                    ValueRole.LocalBinding,
                    valuePath = path,
                    callStack = stack,
                )
                .flowRefined()
        val read =
            ValueSite.fromCompiler(
                    owner,
                    flowRange(30, 40),
                    ValueRole.LocalRead,
                    valuePath = path,
                    callStack = stack,
                )
                .flowRefined()
        assertTrue(remainder(source, read) is Refinement.Refined)
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.BASIS_MISMATCH),
            remainder(source, read.withState(valuePath = ValueAccessPath.Whole).flowRefined()),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.BASIS_MISMATCH),
            remainder(source, read.withState(callStack = ValueCallStack.Empty).flowRefined()),
        )
    }

    private fun remainder(source: ValueSite, read: ValueSite) =
        LocalBindingReadRemainder.admit(
            source,
            RelationSearchBoundary.WORKSPACE_EXPANSION,
            emptySet(),
            setOf(read.identity),
        )
}
