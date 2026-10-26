package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueCallbackArgumentEntryTest {
    @Test
    fun `actual enters anonymous formal preserving active supplier stack path and conditions`() {
        val f = EntryFixture()
        val evidence = ValueSourceTransfer.fromCompiler(f.operation)
        val transfer =
            ValueTransfer.fromCompiler(f.source, f.target, ValueTransferKind.SOURCE_CALL_ENTRY, evidence).flowRefined()
        assertSame(f.source.callStack, transfer.target.callStack)
        assertEquals(listOf(f.frame), transfer.target.callStack.frames)
        assertSame(f.source.valuePath, transfer.target.valuePath)
        assertSame(f.source.conditions, transfer.target.conditions)
        assertEquals(f.parameter.executionOwner, transfer.target.executionOwner)
        assertNotEquals(f.source.executionOwner, transfer.target.executionOwner)
    }

    @Test
    fun `repeated callback arguments enter only their selected execution`() {
        val first = EntryFixture(repeated = true)
        val second = EntryFixture(repeated = true, selected = 1)
        assertNotEquals(first.target.identity, second.target.identity)
        for (fixture in listOf(first, second)) {
            assertEquals(
                fixture.target,
                ValueTransfer.fromCompiler(
                        fixture.source,
                        fixture.target,
                        ValueTransferKind.SOURCE_CALL_ENTRY,
                        ValueSourceTransfer.fromCompiler(fixture.operation),
                    )
                    .flowRefined()
                    .target,
            )
        }
        val crossed = first.operation.copy(parameter = second.parameter)
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                first.source,
                second.target,
                ValueTransferKind.SOURCE_CALL_ENTRY,
                ValueSourceTransfer.fromCompiler(crossed),
            ),
        )
    }

    @Test
    fun `entry rejects wrong actual formal supplier and body`() {
        val f = EntryFixture()
        val changedCall =
            ValueInvocation.fromCompiler(
                    f.frame.invocation.enclosing,
                    flowRange(21, 81),
                    f.frame.invocation.callable,
                )
                .flowRefined()
        val changedFrame = ValueCallFrame.direct(changedCall, f.frame.callee).flowRefined()
        val wrongOperations =
            listOf(
                f.operation.copy(argumentRange = flowRange(331, 335)),
                f.operation.copy(parameter = f.otherParameter),
                f.operation.copy(supplierFrame = changedFrame),
                f.operation.copy(invocation = f.operation.invocation.copy(body = f.fixture.anonymous(90, 120))),
            )
        for (operation in wrongOperations) {
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(
                    f.source,
                    f.target,
                    ValueTransferKind.SOURCE_CALL_ENTRY,
                    ValueSourceTransfer.fromCompiler(operation),
                ),
            )
        }
    }

    @Test
    fun `entry rejects erased target provenance`() {
        val f = EntryFixture()
        val erased =
            ValueSite.fromCompiler(
                    f.target.enclosing,
                    f.target.range,
                    f.target.role,
                    f.target.executionOwner,
                    f.target.valuePath,
                    f.target.callStack,
                )
                .flowRefined()
        for (target in
            listOf(
                erased,
                f.target.withState(valuePath = ValueAccessPath.Whole).flowRefined(),
                f.target.withState(callStack = ValueCallStack.Empty).flowRefined(),
            )) {
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(
                    f.source,
                    target,
                    ValueTransferKind.SOURCE_CALL_ENTRY,
                    ValueSourceTransfer.fromCompiler(f.operation),
                ),
            )
        }
    }

    @Test
    fun `entry requires source evidence and an active supplier frame`() {
        val f = EntryFixture()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(f.source, f.target, ValueTransferKind.SOURCE_CALL_ENTRY),
        )
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                f.source.withState(callStack = ValueCallStack.Empty).flowRefined(),
                f.target.withState(callStack = ValueCallStack.Empty).flowRefined(),
                ValueTransferKind.SOURCE_CALL_ENTRY,
                ValueSourceTransfer.fromCompiler(f.operation),
            ),
        )
    }

    @Test
    fun `argument range contributes to source operation identity`() {
        val f = EntryFixture()
        assertNotEquals(
            buildString { sourceOperation(f.operation) },
            buildString { sourceOperation(f.operation.copy(argumentRange = flowRange(331, 335))) },
        )
    }

    private class EntryFixture(repeated: Boolean = false, selected: Int = 0) {
        val fixture = CallbackInvocationFlowFixture()
        private val wrapper = fixture.endpoint("wrapper", 210, 400, listOf("(Payload,Payload)->Unit"))
        private val call = ValueInvocation.fromCompiler(fixture.caller, flowRange(20, 80), wrapper).flowRefined()
        private val body =
            RelationCallableBody.Anonymous.fromCompiler(
                    fixture.file,
                    flowRange(30, 70),
                    CanonicalCompilerSignature.function(
                            RelationCallableBody.Anonymous.sourceIdentity(
                                fixture.file,
                                flowRange(30, 70),
                            ),
                            null,
                            emptyList(),
                            listOf("Payload", "Payload"),
                            0,
                        )
                        .flowRefined() as CanonicalCompilerSignature.Function,
                )
                .flowRefined()
        private val binding =
            CallbackArgumentBinding.fromCompiler(
                    call,
                    fixture.supplyingOwner,
                    ValueArgumentPosition.parse(0).flowRefined(),
                    fixture.occurrence(225, 245),
                )
                .flowRefined()
        private val invoked =
            CallbackParameterInvocation.fromCompiler(
                    fixture.occurrence(320, 350),
                    RelationCallableBody.Named.fromCompiler(wrapper.evidence).flowRefined(),
                )
                .flowRefined()
        private val invocations =
            if (repeated)
                listOf(
                    invoked,
                    CallbackParameterInvocation.fromCompiler(fixture.occurrence(360, 390), invoked.owner).flowRefined(),
                )
            else listOf(invoked)
        private val flow =
            CallbackInvocationFlow.fromCompiler(
                    fixture.lease.identity,
                    body,
                    CallbackBindingEvidence.Bound(binding),
                    invocations,
                    emptySet(),
                    CallbackInvocationScan.EXHAUSTIVE,
                )
                .flowRefined()
        private val execution =
            ValueExecutionOwner.callback(
                    fixture.caller,
                    body,
                    CompleteCallbackFlowGraph.admit(flow).flowRefined(),
                    binding,
                    invocations[selected],
                )
                .flowRefined()
        val parameter = parameter(0, 32, 37)
        val otherParameter = parameter(1, 38, 43)
        val frame = ValueCallFrame.direct(call, ValueExecutionOwner.declaration(wrapper)).flowRefined()
        private val stack = ValueCallStack.Empty.enter(frame).flowRefined()
        private val receiver =
            ValueSite.fromCompiler(
                    wrapper,
                    flowRange(310, 315),
                    ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.DISPATCH)),
                )
                .flowRefined()
        private val conditions =
            ValueConditions.Unconditional.requiring(
                    ValueCondition.StableReceiver.fromCompiler(
                            receiver,
                            flowRange(326, 330),
                            ValueReceiverKind.DISPATCH,
                            CanonicalCompilerType.fromCompiler("Any").flowRefined(),
                            CanonicalCompilerType.fromCompiler("Payload").flowRefined(),
                        )
                        .flowRefined()
                )
                .flowRefined()
        private val path = flowPath(ValuePathSegment.Element)
        val source =
            ValueSite.fromCompiler(
                    wrapper,
                    if (selected == 0) flowRange(326, 330) else flowRange(366, 370),
                    ValueRole.ExpressionResult,
                    valuePath = path,
                    callStack = stack,
                    conditions = conditions,
                )
                .flowRefined()
        val target =
            ValueSite.fromCompiler(
                    fixture.caller,
                    parameter.range,
                    ValueRole.Formal(ValueFormalPosition.Parameter(parameter)),
                    execution,
                    path,
                    stack,
                    conditions,
                )
                .flowRefined()
        val operation =
            ValueSourceOperation.CallbackArgumentEntry(
                (execution.context as ValueExecutionContext.Callback).selectedInvocation,
                frame,
                parameter,
                source.range,
            )

        private fun parameter(index: Int, start: Int, end: Int) =
            ValueFormalParameter.fromCompiler(
                    fixture.caller,
                    ValueArgumentPosition.parse(index).flowRefined(),
                    SymbolDiscoveryCandidateName.parse("arg$index").flowRefined(),
                    flowRange(start, end),
                    execution,
                )
                .flowRefined()
    }
}
