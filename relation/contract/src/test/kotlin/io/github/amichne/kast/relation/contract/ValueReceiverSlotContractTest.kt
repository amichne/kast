package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.SymbolSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Detached slot laws. Native fixtures separately establish the actual compiler receiver identities. */
class ValueReceiverSlotContractTest {
    @Test
    fun `two receiver slots at one call have distinct value identities`() {
        val fixture = Fixture()
        val extension = fixture.actual(ValueReceiverKind.EXTENSION)
        val dispatch = fixture.actual(ValueReceiverKind.DISPATCH)
        assertEquals(extension.range, dispatch.range)
        assertSame((extension.role as ValueRole.Receiver).call, (dispatch.role as ValueRole.Receiver).call)
        assertNotEquals(extension.identity, dispatch.identity)
    }

    @Test
    fun `canonical value evidence keeps both receiver slots at one call`() {
        val fixture = Fixture()
        val extension = fixture.actual(ValueReceiverKind.EXTENSION)
        val dispatch = fixture.actual(ValueReceiverKind.DISPATCH)
        assertEquals(extension.range, dispatch.range)
        assertSame((extension.role as ValueRole.Receiver).call, (dispatch.role as ValueRole.Receiver).call)
        assertNotEquals(buildString { site(extension) }, buildString { site(dispatch) })
    }

    @Test
    fun `each receiver slot enters only its own formal without a new call`() {
        val fixture = Fixture()
        for (kind in ValueReceiverKind.entries) {
            val actual = fixture.actual(kind)
            val formal = fixture.formal(kind)
            val transferred = fixture.enter(actual, formal, kind).flowRefined()
            assertSame(actual, transferred.source)
            assertSame(formal, transferred.target)
            assertEquals(listOf(fixture.frame), transferred.target.callStack.frames)
        }
    }

    @Test
    fun `a receiver cannot enter the other slot at the same exact call`() {
        val fixture = Fixture()
        for (kind in ValueReceiverKind.entries) {
            val other = kind.other()
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                fixture.enter(fixture.actual(kind), fixture.formal(other), other),
            )
        }
    }

    @Test
    fun `fresh receiver admission keeps a matching claimed slot`() {
        val fixture = Fixture()
        for (kind in ValueReceiverKind.entries) {
            val actual = fixture.actual(kind)
            val admitted = RevalidatedValueSite.fromCompiler(fixture.request(kind), actual).flowRefined()
            assertSame(actual, admitted.site)
            assertEquals(kind, (admitted.site.role as ValueRole.Receiver).kind)
        }
    }

    @Test
    fun `fresh receiver admission rejects a different claimed slot`() {
        val fixture = Fixture()
        for (kind in ValueReceiverKind.entries) {
            assertEquals(
                Refinement.Rejected(ValueSiteRevalidationFailure.RECEIVER_KIND_MISMATCH),
                RevalidatedValueSite.fromCompiler(fixture.request(kind.other()), fixture.actual(kind)),
            )
        }
    }

    private class Fixture {
        private val host = flowEndpoint("host", 0, 200) as RelationEndpoint.Resolved
        private val callee = flowEndpoint("memberExtension", 300, 380) as RelationEndpoint.Resolved
        private val invocation = ValueInvocation.fromCompiler(host, flowRange(30, 50), callee).flowRefined()
        val frame = ValueCallFrame.direct(invocation, ValueExecutionOwner.declaration(callee)).flowRefined()
        private val stack = ValueCallStack.Empty.enter(frame).flowRefined()

        fun actual(kind: ValueReceiverKind): ValueSite =
            ValueSite.fromCompiler(host, invocation.range, ValueRole.Receiver(invocation, kind)).flowRefined()

        fun formal(kind: ValueReceiverKind): ValueSite =
            ValueSite.fromCompiler(
                    enclosing = callee,
                    range = callee.range,
                    role = ValueRole.Formal(ValueFormalPosition.Receiver(kind)),
                    callStack = stack,
                )
                .flowRefined()

        fun enter(
            actual: ValueSite,
            formal: ValueSite,
            kind: ValueReceiverKind,
        ): Refinement<ValueTransfer, ValueTransferFailure> =
            ValueTransfer.fromCompiler(
                source = actual,
                target = formal,
                kind = ValueTransferKind.SOURCE_CALL_ENTRY,
                evidence =
                    ValueSourceTransfer.fromCompiler(
                        ValueSourceOperation.Enter(frame, ValueFormalPosition.Receiver(kind))
                    ),
            )

        fun request(kind: ValueReceiverKind): ValueSiteRevalidationRequest =
            ValueSiteRevalidationRequest.create(
                    enclosing = SymbolSelector.issue(host.lease, host.scope, host.evidence),
                    anchor = invocation.range,
                    role =
                        ValueSiteRoleClaim.Receiver(
                            invocation.range,
                            SymbolSelector.issue(callee.lease, callee.scope, callee.evidence),
                            kind,
                        ),
                    budget =
                        RelationBudget(
                            ResourceBudget(
                                ResultLimit.parse(16).flowRefined(),
                                WorkUnitLimit.parse(32).flowRefined(),
                                ElapsedTimeLimitMillis.parse(1_000).flowRefined(),
                            ),
                            RelationByteLimit.parse(100_000).flowRefined(),
                        ),
                )
                .flowRefined()
    }
}

private fun ValueReceiverKind.other(): ValueReceiverKind =
    when (this) {
        ValueReceiverKind.EXTENSION -> ValueReceiverKind.DISPATCH
        ValueReceiverKind.DISPATCH -> ValueReceiverKind.EXTENSION
    }
