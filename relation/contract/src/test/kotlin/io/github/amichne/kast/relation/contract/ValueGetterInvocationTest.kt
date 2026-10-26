package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.SymbolSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValueGetterInvocationTest {
    @Test
    fun `property receiver claim can be revalidated but cannot supply a function argument`() {
        val owner = flowEndpoint("owner", 0, 100) as RelationEndpoint.Resolved
        val property =
            flowEndpoint(name = "Box.selected", start = 200, end = 260, propertyType = "String")
                as RelationEndpoint.Resolved
        val anchor = flowRange(10, 20)
        val expected = SymbolSelector.issue(property.lease, property.scope, property.evidence)
        val budget =
            RelationBudget(
                ResourceBudget(
                    ResultLimit.parse(20).flowRefined(),
                    WorkUnitLimit.parse(10).flowRefined(),
                    ElapsedTimeLimitMillis.parse(1000).flowRefined(),
                ),
                RelationByteLimit.parse(100000).flowRefined(),
            )
        val request =
            ValueSiteRevalidationRequest.create(
                    enclosing = SymbolSelector.issue(owner.lease, owner.scope, owner.evidence),
                    anchor = anchor,
                    role = ValueSiteRoleClaim.Receiver(anchor, expected, ValueReceiverKind.DISPATCH),
                    budget = budget,
                )
                .flowRefined()
        val getter = ValueInvocation.getterFromCompiler(owner, anchor, property).flowRefined()
        val site =
            ValueSite.fromCompiler(owner, anchor, ValueRole.Receiver(getter, ValueReceiverKind.DISPATCH)).flowRefined()
        assertSame(site, RevalidatedValueSite.fromCompiler(request, site).flowRefined().site)
        assertEquals(
            Refinement.Rejected(ValueSiteRevalidationRequestFailure.ARGUMENT_POSITION_UNAVAILABLE),
            ValueSiteRevalidationRequest.create(
                enclosing = request.enclosing,
                anchor = anchor,
                role = ValueSiteRoleClaim.Argument(anchor, expected, ValueArgumentPosition.parse(0).flowRefined()),
                budget = budget,
            ),
        )
    }

    @Test
    fun `getter keeps exact property identity without admitting an ordinary function call or argument`() {
        val owner = flowEndpoint("owner", 0, 100)
        val property = flowEndpoint(name = "Box.selected", start = 200, end = 260, propertyType = "String")
        val anchor = flowRange(10, 20)
        assertEquals(
            Refinement.Rejected(ValueInvocationFailure.NOT_CALLABLE),
            ValueInvocation.fromCompiler(owner, anchor, property),
        )
        val getter = ValueInvocation.getterFromCompiler(owner, anchor, property).flowRefined()
        assertTrue(getter.operation is ValueInvocationOperation.Getter)
        assertSame(property.signature, getter.signature)
        assertEquals(property.valueIdentity, getter.identity.callable)
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.INVALID_ARGUMENT_POSITION),
            ValueSite.fromCompiler(
                owner,
                anchor,
                ValueRole.Argument(getter, ValueArgumentPosition.parse(0).flowRefined()),
            ),
        )
        assertEquals(
            Refinement.Rejected(ValueInvocationFailure.NOT_CALLABLE),
            ValueInvocation.getterFromCompiler(owner, anchor, flowEndpoint("function", 200, 260)),
        )
    }

    @Test
    fun `getter preserves authority and owner validation`() {
        val owner = flowEndpoint("owner", 0, 100)
        val property = flowEndpoint(name = "Box.selected", start = 200, end = 260, propertyType = "String")
        assertEquals(
            Refinement.Rejected(ValueInvocationFailure.ANCHOR_OUTSIDE_OWNER),
            ValueInvocation.getterFromCompiler(owner, flowRange(120, 130), property),
        )
        val foreign =
            flowEndpoint(name = "Box.selected", start = 200, end = 260, generation = 2, propertyType = "String")
        assertEquals(
            Refinement.Rejected(ValueInvocationFailure.BASIS_MISMATCH),
            ValueInvocation.getterFromCompiler(owner, flowRange(10, 20), foreign),
        )
    }

    @Test
    fun `getter sharing and direct frames retain the selected operation and reject another property`() {
        val owner = flowEndpoint("owner", 0, 100)
        val property = flowEndpoint(name = "Box.selected", start = 200, end = 260, propertyType = "String")
        val other = flowEndpoint(name = "Box.other", start = 270, end = 290, propertyType = "String")
        val getter = ValueInvocation.getterFromCompiler(owner, flowRange(10, 20), property).flowRefined()
        val shared = getter.shareCallableEvidence(ValueFlowProofSharing())
        assertEquals(getter.identity, shared.identity)
        assertSame(getter.operation, shared.operation)
        val frame = ValueCallFrame.direct(shared, ValueExecutionOwner.declaration(property)).flowRefined()
        val stack = ValueCallStack.Empty.enter(frame).flowRefined()
        assertEquals(ValueCallStack.Empty, stack.exit(frame).flowRefined())
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.CALLABLE_MISMATCH),
            ValueCallFrame.direct(shared, ValueExecutionOwner.declaration(other)),
        )
    }
}
