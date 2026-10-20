package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueLetCallbackOracleTest {
    @Test
    fun `reviewed input enters the actual anonymous formal with exact state and model proof`() {
        val f = ValueLetCallbackOracleCase()
        val source = f.receiver()
        val frame = f.frame()
        val application = ValueModelApplication.ScopeCallbackInput.enter(source, frame).flowRefined()
        assertSame(source, application.source)
        assertSame(f.binding, application.binding)
        assertSame(frame, application.frame)
        assertSame(f.body, application.target.executionOwner)
        assertEquals(f.body.range, application.target.range)
        assertEquals(ValueRole.Formal(ValueFormalPosition.Parameter(f.formal)), application.target.role)
        assertEquals(source.valuePath, application.target.valuePath)
        assertEquals(source.predicates, application.target.predicates)
        assertEquals(source.callStack.frames + frame, application.target.callStack.frames)
        val proof = frame.target as ValueReviewedCallbackTarget
        assertSame(f.binding, proof.binding)
        assertSame(f.formal, proof.formal)
        assertSame(f.argument, proof.argument)
    }

    @Test
    fun `reviewed normal return carries the returned path and pops only its matched call`() {
        val f = ValueLetCallbackOracleCase()
        val frame = f.frame()
        val source = f.returned(frame)
        val application = ValueModelApplication.ScopeCallbackReturn.leave(source, frame).flowRefined()
        assertSame(source, application.source)
        assertSame(f.binding, application.binding)
        assertEquals(ValueRole.ExpressionResult, application.target.role)
        assertEquals(f.call.range, application.target.range)
        assertSame(f.call.executionOwner, application.target.executionOwner)
        assertEquals(ValueAccessPath.Whole, application.target.valuePath)
        assertEquals(source.predicates, application.target.predicates)
        assertEquals(f.metadata.stack, application.target.callStack)
    }

    @Test
    fun `wrong call and dispatch receiver cannot borrow reviewed callback input`() {
        val f = ValueLetCallbackOracleCase()
        assertEquals(
            Refinement.Rejected(ValueModelApplicationFailure.SOURCE_MISMATCH),
            ValueModelApplication.ScopeCallbackInput.enter(f.receiver(invocation = f.metadata.call()), f.frame()),
        )
        assertEquals(
            Refinement.Rejected(ValueModelApplicationFailure.RECEIVER_KIND_MISMATCH),
            ValueModelApplication.ScopeCallbackInput.enter(f.receiver(kind = ValueReceiverKind.DISPATCH), f.frame()),
        )
    }

    @Test
    fun `a return without the matching top frame cannot reach a reviewed result`() {
        val f = ValueLetCallbackOracleCase()
        val frame = f.frame()
        val source = f.returned(frame).withState(callStack = f.metadata.stack).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueModelApplicationFailure.SOURCE_MISMATCH),
            ValueModelApplication.ScopeCallbackReturn.leave(source, frame),
        )
        val expression =
            ValueSite.fromCompiler(
                    f.host,
                    flowRange(54, 56),
                    ValueRole.ExpressionResult,
                    f.body,
                    callStack = f.returned(frame).callStack,
                )
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueModelApplicationFailure.SOURCE_MISMATCH),
            ValueModelApplication.ScopeCallbackReturn.leave(expression, frame),
        )
    }

    @Test
    fun `another argument range cannot establish the actual anonymous callback`() {
        val f = ValueLetCallbackOracleCase()
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.CALLBACK_PROOF_MISMATCH),
            ValueCallFrame.reviewedCallback(
                f.binding,
                RelationOccurrence.fromSource(f.host.file, flowRange(51, 58)),
                f.formal,
            ),
        )
    }

    @Test
    fun `collection selection model cannot establish a scope callback frame`() {
        val f = ValueLetCallbackOracleCase()
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.CALLBACK_PROOF_MISMATCH),
            ValueCallFrame.reviewedCallback(f.metadata.binding(), f.argument, f.formal),
        )
    }

    @Test
    fun `a formal with the same identity cannot erase its changed source scope`() {
        val f = ValueLetCallbackOracleCase()
        val host = changedHost(f)
        val body = f.body.body as ValueExecutionBody.Anonymous
        val execution = ValueExecutionOwner.fromCompiler(host, body.callable, host.lease.identity).flowRefined()
        val formal =
            ValueFormalParameter.fromCompilerImplicit(
                    execution,
                    ValueArgumentPosition.parse(0).flowRefined(),
                    SymbolDiscoveryCandidateName.parse("it").flowRefined(),
                )
                .flowRefined()
        assertEquals(f.host.valueIdentity, host.valueIdentity)
        assertEquals(f.formal, formal)
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.CALLBACK_PROOF_MISMATCH),
            ValueCallFrame.reviewedCallback(f.binding, f.argument, formal),
        )
    }

    @Test
    fun `a return with unchanged body identity cannot erase its changed enclosing proof`() {
        val f = ValueLetCallbackOracleCase()
        val host = changedHost(f)
        val body = f.body.body as ValueExecutionBody.Anonymous
        val execution = ValueExecutionOwner.fromCompiler(host, body.callable, host.lease.identity).flowRefined()
        val frame = f.frame()
        val source =
            ValueSite.fromCompiler(
                    enclosing = host,
                    range = flowRange(54, 56),
                    role = ValueRole.Return,
                    executionOwner = execution,
                    callStack = f.returned(frame).callStack,
                )
                .flowRefined()
        assertEquals(f.body, source.executionOwner)
        assertEquals(
            Refinement.Rejected(ValueModelApplicationFailure.SOURCE_MISMATCH),
            ValueModelApplication.ScopeCallbackReturn.leave(source, frame),
        )
    }

    private fun changedHost(f: ValueLetCallbackOracleCase): RelationEndpoint {
        val original = f.host as RelationEndpoint.Resolved
        val scope =
            (original.scope as SymbolSearchScope.Workspace).copy(sourceKinds = SymbolSourceKindPolicy.PRODUCTION_ONLY)
        return RelationEndpoint.resolve(original.lease, scope, original.evidence, original.constraints).flowRefined()
    }

    @Test
    fun `reviewed callback input and return cannot acquire native source transfer provenance`() {
        val f = ValueLetCallbackOracleCase()
        val frame = f.frame()
        val entry = ValueModelApplication.ScopeCallbackInput.enter(f.receiver(), frame).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                entry.source,
                entry.target,
                ValueTransferKind.SOURCE_CALL_ENTRY,
                ValueSourceTransfer.fromCompiler(
                    ValueSourceOperation.Enter(frame, ValueFormalPosition.Parameter(f.formal))
                ),
            ),
        )
        val leave = ValueModelApplication.ScopeCallbackReturn.leave(f.returned(frame), frame).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                leave.source,
                leave.target,
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(frame)),
            ),
        )
    }
}
