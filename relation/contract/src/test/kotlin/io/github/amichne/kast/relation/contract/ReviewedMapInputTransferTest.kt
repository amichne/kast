package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedMapInputTransferTest {
    @Test
    fun `callback input rejects another callback owner and stale source epoch`() {
        val f = MapInputFixture()
        val foreign = MapInputFixture(2)
        val other = f.base.binding(body = f.base.body(435, 459)).flowRefined()
        for ((source, formal) in
            listOf(
                f.receiver() to foreign.formal(),
                foreign.receiver() to f.formal(),
                f.receiver() to f.formal(other),
            )) {
            assertEquals(
                Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
                ReviewedValueTransfer.callbackInput(source, f.base.binding, formal),
            )
        }
    }

    @Test
    fun `callback input rejects another invocation receiver slot and callback range`() {
        val f = MapInputFixture()
        val forbidden =
            listOf(
                f.receiver(kind = ValueReceiverKind.DISPATCH),
                f.receiver(callRange = flowRange(423, 459)),
                f.receiver(range = flowRange(435, 437)),
                f.base.source(),
            )
        for (source in forbidden) {
            assertEquals(
                Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
                ReviewedValueTransfer.callbackInput(source, f.base.binding, f.formal()),
            )
        }
    }

    @Test
    fun `formal construction rejects the wrong parameter and an out of body range`() {
        val f = MapInputFixture()
        val owner = ValueExecutionOwner.reviewedCallback(f.base.binding)
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.INVALID_PARAMETER),
            ValueFormalParameter.fromCompiler(
                callable = f.base.enclosing,
                position = f.base.position(1),
                name = SymbolDiscoveryCandidateName.parse("second").flowRefined(),
                range = flowRange(436, 437),
                executionOwner = owner,
            ),
        )
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.PARAMETER_OUTSIDE_CALLABLE),
            ValueFormalParameter.fromCompiler(
                callable = f.base.enclosing,
                position = f.base.position(0),
                name = SymbolDiscoveryCandidateName.parse("element").flowRefined(),
                range = flowRange(424, 433),
                executionOwner = owner,
            ),
        )
    }

    @Test
    fun `a receiver field cannot become a callback element`() {
        val f = MapInputFixture()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
            ReviewedValueTransfer.callbackInput(f.receiver(path = f.base.fieldPath), f.base.binding, f.formal()),
        )
    }

    @Test
    fun `input formal cannot skip a constant callback body`() {
        val f = MapInputFixture()
        val input = ReviewedValueTransfer.callbackInput(f.receiver(), f.base.binding, f.formal()).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.callbackResult(input.target, f.base.binding),
        )
        for (kind in ValueTransferKind.entries) {
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(input.source, input.target, kind),
            )
        }
    }

    @Test
    fun `selected input element preserves the entire nested component suffix`() {
        val f = MapInputFixture()
        val path = flowPath(ValuePathSegment.Element, ValuePathSegment.Field(f.base.field), ValuePathSegment.Element)
        val source = f.receiver(path = path)
        val formal = f.formal()
        val transfer = ReviewedValueTransfer.callbackInput(source, f.base.binding, formal).flowRefined()
        assertSame(source, transfer.source)
        assertEquals(ValueRole.Formal(ValueFormalPosition.Parameter(formal)), transfer.target.role)
        assertEquals(formal.range, transfer.target.range)
        assertEquals(formal.executionOwner, transfer.target.executionOwner)
        assertEquals(path.segments.drop(1), transfer.target.valuePath.segments)
        assertEquals(
            ReviewedValueSemantics.CONDITIONAL_INPUT_ELEMENT_TO_CALLBACK_PARAMETER,
            (transfer.relation as ReviewedValueRelation.CallbackCorrespondence).semantics,
        )
        assertEquals(
            ReviewedCallbackInvocationEvidence.Unestablished,
            (transfer.binding as ReviewedCallbackValueBinding).invocationEvidence,
        )
    }

    @Test
    fun `whole receiver content has its own derived relationship`() {
        val f = MapInputFixture()
        val content =
            ReviewedValueTransfer.callbackInput(f.receiver(path = ValueAccessPath.Whole), f.base.binding, f.formal())
                .flowRefined()
        val element =
            ReviewedValueTransfer.callbackInput(
                    f.receiver(path = flowPath(ValuePathSegment.Element)),
                    f.base.binding,
                    f.formal(),
                )
                .flowRefined()
        assertEquals(ValueAccessPath.Whole, content.target.valuePath)
        assertEquals(ValueAccessPath.Whole, element.target.valuePath)
        assertEquals(
            ReviewedValueSemantics.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER,
            (content.relation as ReviewedValueRelation.CallbackCorrespondence).semantics,
        )
        assertNotEquals(
            (content.relation as ReviewedValueRelation.CallbackCorrespondence).semantics,
            (element.relation as ReviewedValueRelation.CallbackCorrespondence).semantics,
        )
        assertNotEquals(content, element)
    }

    @Test
    fun `input transfer keeps existing conditions and call frames`() {
        val f = MapInputFixture()
        val caller = flowEndpoint("outer", 0, 100)
        val call = ValueInvocation.fromCompiler(caller, flowRange(20, 60), f.base.enclosing).flowRefined()
        val frame = ValueCallFrame.direct(call, ValueExecutionOwner.declaration(f.base.enclosing)).flowRefined()
        val stack = ValueCallStack.Empty.enter(frame).flowRefined()
        val receiver = f.receiver().withState(callStack = stack).flowRefined()
        val cast =
            ValueCondition.CastSuccess.fromCompiler(
                    source = receiver,
                    useRange = receiver.range,
                    kind = ValueCastKind.UNSAFE,
                    originalType = CanonicalCompilerType.fromCompiler("kotlin/Any").flowRefined(),
                    targetType = CanonicalCompilerType.fromCompiler("kotlin/collections/List").flowRefined(),
                )
                .flowRefined()
        val source = receiver.withState(conditions = receiver.conditions.requiring(cast).flowRefined()).flowRefined()
        val transfer = ReviewedValueTransfer.callbackInput(source, f.base.binding, f.formal()).flowRefined()
        assertEquals(listOf(cast), transfer.target.conditions.facts)
        assertEquals(listOf(frame), transfer.target.callStack.frames)
        assertEquals(source.conditions, transfer.target.conditions)
    }

    @Test
    fun `proof sharing keeps input semantics equality and charged evidence`() {
        val f = MapInputFixture()
        val transfer = ReviewedValueTransfer.callbackInput(f.receiver(), f.base.binding, f.formal()).flowRefined()
        val repeated = ReviewedValueTransfer.callbackInput(f.receiver(), f.base.binding, f.formal()).flowRefined()
        assertEquals(transfer, repeated)
        assertEquals(transfer.hashCode(), repeated.hashCode())
        val step =
            ValueFlowStep.fromEvidence(
                    source = transfer.source,
                    transfers = emptyList(),
                    obligations = emptyList(),
                    terminal = ValueFlowTerminal.SupportedDomainExhausted,
                    domain = f.base.domain(),
                    examinedWorkUnits = RelationWorkCount.parse(1).flowRefined(),
                    reviewedTransfers = listOf(transfer),
                )
                .flowRefined()
        val shared = step.shareCallableEvidence(listOf(step))
        assertEquals(listOf(transfer), shared.reviewedTransfers)
        assertEquals(
            (transfer.relation as ReviewedValueRelation.CallbackCorrespondence).semantics,
            (shared.reviewedTransfers.single().relation as ReviewedValueRelation.CallbackCorrespondence).semantics,
        )
        assertEquals(step.retainedBytes, step.receipts.single().returnedBytes.value)
        assertTrue(
            transfer.retainedBytes >=
                transfer.binding.retainedBytes + transfer.source.retainedBytes + transfer.target.retainedBytes
        )
        assertEquals(1, step.outputCount)
    }
}

private class MapInputFixture(generation: Long = 1) {
    val base = ReviewedValueFixture(generation)

    fun formal(binding: ReviewedCallbackValueBinding = base.binding): ValueFormalParameter =
        ValueFormalParameter.fromCompiler(
                callable = base.enclosing,
                position = base.position(0),
                name = SymbolDiscoveryCandidateName.parse("element").flowRefined(),
                range = flowRange(436, 457),
                executionOwner = ValueExecutionOwner.reviewedCallback(binding),
            )
            .flowRefined()

    fun receiver(
        path: ValueAccessPath = flowPath(ValuePathSegment.Element, ValuePathSegment.Field(base.field)),
        kind: ValueReceiverKind = ValueReceiverKind.EXTENSION,
        range: ExactDeclarationTextRange = flowRange(424, 433),
        callRange: ExactDeclarationTextRange = base.occurrence.range,
    ): ValueSite {
        val target =
            RelationEndpoint.resolve(base.enclosing.lease, base.enclosing.scope, base.selected.target).flowRefined()
        val call = ValueInvocation.fromCompiler(base.enclosing, callRange, target).flowRefined()
        return ValueSite.fromCompiler(
                enclosing = base.enclosing,
                range = range,
                role = ValueRole.Receiver(call, kind),
                valuePath = path,
            )
            .flowRefined()
    }
}
