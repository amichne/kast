package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.symbol.contract.SymbolSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueLoopTransferTest {
    @Test
    fun `loop results are phase distinct and cannot move their selected invocation anchor`() {
        val protocol = protocol()
        val iterator = site(protocol.iteratorResult)
        val element = site(ValueLoopIteration.fromCompilerNormalTrue(protocol).elementResult)
        assertNotEquals(iterator.identity, element.identity)
        assertNotEquals(iterator.identity, protocol.iterator.resultSite().identity)
        assertNotEquals(element.identity, protocol.next.resultSite().identity)
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH),
            ValueSite.fromCompiler(iterator.enclosing, flowRange(21, 80), iterator.role),
        )
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH),
            ValueSite.fromCompiler(flowEndpoint("other", 0, 200), iterator.range, iterator.role),
        )
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH),
            ValueSite.fromCompiler(flowEndpoint("caller", 0, 200, generation = 2), iterator.range, iterator.role),
        )
    }

    @Test
    fun `source returns select exactly iterator or next and consume only their own frame`() {
        val protocol = protocol()
        val results = listOf(protocol.iteratorResult, ValueLoopIteration.fromCompilerNormalTrue(protocol).elementResult)
        for (result in results) {
            val call = result.invocation
            val frame = ValueCallFrame.direct(call, ValueExecutionOwner.declaration(call.callable)).flowRefined()
            val returned =
                ValueSite.fromCompiler(
                        call.callable,
                        call.callable.range,
                        ValueRole.Return,
                        callStack = ValueCallStack.Empty.enter(frame).flowRefined(),
                    )
                    .flowRefined()
            val evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(frame))
            assertEquals(
                site(result),
                ValueTransfer.fromCompiler(
                        returned,
                        site(result),
                        ValueTransferKind.SOURCE_CALL_RETURN,
                        evidence,
                    )
                    .flowRefined()
                    .target,
            )
            val other = results.single { it != result }
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(returned, site(other), ValueTransferKind.SOURCE_CALL_RETURN, evidence),
            )
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(
                    returned,
                    site(protocol(start = 90).iteratorResult),
                    ValueTransferKind.SOURCE_CALL_RETURN,
                    evidence,
                ),
            )
        }
    }

    @Test
    fun `guard return cannot produce a loop element`() {
        val protocol = protocol()
        val guard = protocol.hasNext
        val frame = ValueCallFrame.direct(guard, ValueExecutionOwner.declaration(guard.callable)).flowRefined()
        val source =
            ValueSite.fromCompiler(
                    guard.callable,
                    guard.callable.range,
                    ValueRole.Return,
                    callStack = ValueCallStack.Empty.enter(frame).flowRefined(),
                )
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                source,
                site(ValueLoopIteration.fromCompilerNormalTrue(protocol).elementResult),
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(frame)),
            ),
        )
    }

    @Test
    fun `advance retains path stack and conditions`() {
        val (source, target, evidence) = advanceFixture()
        val admitted = ValueTransfer.fromCompiler(source, target, ValueTransferKind.RECEIVER, evidence).flowRefined()
        assertSame(source.valuePath, admitted.target.valuePath)
        assertSame(source.callStack, admitted.target.callStack)
        assertSame(source.conditions, admitted.target.conditions)
    }

    @Test
    fun `advance requires the selected guard and receiver slot`() {
        val (source, target, evidence) = advanceFixture()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(source, target, ValueTransferKind.RECEIVER),
        )
        for (proof in
            listOf(
                ValueLoopTransfer.Advance.fromCompiler(evidence.iteration, ValueReceiverKind.EXTENSION),
                ValueLoopTransfer.Advance.fromCompiler(
                    ValueLoopIteration.fromCompilerNormalTrue(protocol(start = 90)),
                    ValueReceiverKind.DISPATCH,
                ),
            )) {
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(source, target, ValueTransferKind.RECEIVER, proof),
            )
        }
    }

    @Test
    fun `advance rejects erased path stack and conditions`() {
        val (source, target, evidence) = advanceFixture()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                source,
                target.withState(valuePath = ValueAccessPath.Whole).flowRefined(),
                ValueTransferKind.RECEIVER,
                evidence,
            ),
        )
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                source,
                target.withState(callStack = ValueCallStack.Empty).flowRefined(),
                ValueTransferKind.RECEIVER,
                evidence,
            ),
        )
        val erased =
            ValueSite.fromCompiler(
                    target.enclosing,
                    target.range,
                    target.role,
                    valuePath = source.valuePath,
                    callStack = source.callStack,
                )
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(source, erased, ValueTransferKind.RECEIVER, evidence),
        )
    }

    @Test
    fun `only element can enter a contained component receiver with Direct evidence`() {
        val protocol = protocol()
        val element = site(ValueLoopIteration.fromCompilerNormalTrue(protocol).elementResult)
        val component =
            ValueInvocation.fromCompiler(
                    element.enclosing,
                    flowRange(25, 30),
                    flowEndpoint("Packet.component1", 500, 530),
                )
                .flowRefined()
        val target =
            ValueSite.fromCompiler(
                    element.enclosing,
                    component.range,
                    ValueRole.Receiver(component, ValueReceiverKind.DISPATCH),
                )
                .flowRefined()
        assertEquals(
            target,
            ValueTransfer.fromCompiler(element, target, ValueTransferKind.RECEIVER).flowRefined().target,
        )
        for (source in
            listOf(
                site(protocol.iteratorResult),
                site(ValueLoopIteration.fromCompilerNormalTrue(protocol(start = 90)).elementResult),
            )) {
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(source, target, ValueTransferKind.RECEIVER),
            )
        }
        val wrong =
            ValueSite.fromCompiler(
                    element.enclosing,
                    protocol.next.range,
                    ValueRole.Receiver(protocol.next, ValueReceiverKind.DISPATCH),
                )
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(element, wrong, ValueTransferKind.RECEIVER),
        )
    }

    @Test
    fun `readmission claim preserves result phase and all selected callables including guard`() {
        val protocol = protocol()
        val claim =
            ValueSiteRoleClaim.LoopResult(
                protocol.next.range,
                selector(protocol.iterator.callable),
                selector(protocol.hasNext.callable),
                selector(protocol.next.callable),
                ValueLoopResultPhase.ELEMENT,
            )
        val role = ValueRole.LoopResult(ValueLoopIteration.fromCompilerNormalTrue(protocol).elementResult)
        val owner = selector(protocol.next.enclosing)
        assertEquals(Refinement.Refined(Unit), claim.admitLoopRole(role, owner))
        assertEquals(
            Refinement.Rejected(ValueSiteRevalidationFailure.ROLE_MISMATCH),
            claim.copy(phase = ValueLoopResultPhase.ITERATOR).admitLoopRole(role, owner),
        )
        assertEquals(
            Refinement.Rejected(ValueSiteRevalidationFailure.CALLABLE_MISMATCH),
            claim.copy(expectedHasNext = claim.expectedNext).admitLoopRole(role, owner),
        )
        assertEquals(
            Refinement.Rejected(ValueSiteRevalidationFailure.ROLE_MISMATCH),
            claim.admitLoopRole(ValueRole.ExpressionResult, owner),
        )
    }

    private fun selector(endpoint: RelationEndpoint): SymbolSelector {
        val resolved = endpoint as RelationEndpoint.Resolved
        return SymbolSelector.issue(resolved.lease, resolved.scope, resolved.evidence)
    }
}

private data class LoopAdvanceFixture(
    val source: ValueSite,
    val target: ValueSite,
    val evidence: ValueLoopTransfer.Advance,
)

private fun advanceFixture(): LoopAdvanceFixture {
    val protocol = protocol()
    val iteration = ValueLoopIteration.fromCompilerNormalTrue(protocol)
    val evidence = ValueLoopTransfer.Advance.fromCompiler(iteration, ValueReceiverKind.DISPATCH)
    val receiver =
        ValueSite.fromCompiler(
                protocol.next.enclosing,
                protocol.next.range,
                ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.DISPATCH)),
            )
            .flowRefined()
    val condition =
        ValueCondition.StableReceiver.fromCompiler(
                receiver,
                receiver.range,
                ValueReceiverKind.DISPATCH,
                CanonicalCompilerType.fromCompiler("Any").flowRefined(),
                CanonicalCompilerType.fromCompiler("Cursor").flowRefined(),
            )
            .flowRefined()
    val conditions = ValueConditions.Unconditional.requiring(condition).flowRefined()
    val path = flowPath(ValuePathSegment.Element)
    val frame =
        ValueCallFrame.direct(
                protocol.iterator,
                ValueExecutionOwner.declaration(protocol.iterator.callable),
            )
            .flowRefined()
    val stack = ValueCallStack.Empty.enter(frame).flowRefined()
    val source = site(protocol.iteratorResult).withState(path, stack, conditions).flowRefined()
    val target =
        ValueSite.fromCompiler(
                source.enclosing,
                source.range,
                ValueRole.Receiver(protocol.next, ValueReceiverKind.DISPATCH),
                valuePath = path,
                callStack = stack,
                conditions = conditions,
            )
            .flowRefined()
    return LoopAdvanceFixture(source, target, evidence)
}

private fun protocol(start: Int = 20): ValueLoopProtocol {
    val owner = flowEndpoint("caller", 0, 200)
    fun call(name: String, offset: Int) =
        ValueInvocation.fromCompiler(
                owner,
                flowRange(start, start + 60),
                flowEndpoint(name, offset, offset + 30),
            )
            .flowRefined()
    return ValueLoopProtocol.fromCompiler(
            call("Source.iterator", 300),
            call("Cursor.hasNext", 340),
            call("Cursor.next", 380),
        )
        .flowRefined()
}

private fun site(result: ValueLoopResult): ValueSite =
    ValueSite.fromCompiler(
            result.invocation.enclosing,
            result.invocation.range,
            ValueRole.LoopResult(result),
        )
        .flowRefined()
