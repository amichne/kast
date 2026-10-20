package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueCallbackTransferOracleTest {
    @Test
    fun `a builtin argument enters the exact implicit formal and keeps its field and scoped fact`() {
        val case = ValueCallbackOracleCase()
        val argument = case.argument()
        val predicate = ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(argument))
        val predicates = ValuePredicates.fromCompiler(listOf(predicate)).flowRefined()
        val path = flowPath(ValuePathSegment.Field(flowField("Response.value", 700, 720)))
        val source = argument.withState(valuePath = path, predicates = predicates).flowRefined()
        val target = case.entered().withState(valuePath = path, predicates = predicates).flowRefined()
        val transfer = entry(case, source, target).flowRefined()
        assertEquals(ValueFormalOrigin.Implicit, case.formal.origin)
        assertEquals(case.callback.range, transfer.target.range)
        assertSame(case.callback, transfer.target.executionOwner)
        assertSame(path, transfer.target.valuePath)
        assertSame(predicates, transfer.target.predicates)
        assertEquals(listOf(case.supplyingFrame, case.frame()), transfer.target.callStack.frames)
    }

    @Test
    fun `a callback entry needs its exact supplying frame at the top of the active stack`() {
        val case = ValueCallbackOracleCase()
        val missing = case.argument(ValueCallStack.Empty)
        val missingTarget = case.entered(stack = ValueCallStack.Empty.enter(case.frame()).flowRefined())
        rejectEntry(case, missing, missingTarget)
        val other = ValueCallbackOracleCase(supplyStart = 10)
        val wrongStack = ValueCallStack.Empty.enter(other.supplyingFrame).flowRefined()
        val wrong = case.argument(wrongStack)
        val wrongTarget = case.entered(stack = wrongStack.enter(case.frame()).flowRefined())
        rejectEntry(case, wrong, wrongTarget)
    }

    @Test
    fun `a callback entry cannot replace the formal with a sibling anonymous formal`() {
        val case = ValueCallbackOracleCase()
        val sibling = callbackOracleOwner(case.host, 70, 90)
        val formal =
            ValueFormalParameter.fromCompilerImplicit(
                    sibling,
                    callbackOracleIndex(),
                    SymbolDiscoveryCandidateName.parse("it").flowRefined(),
                )
                .flowRefined()
        val position = ValueFormalPosition.Parameter(formal)
        val target =
            ValueSite.fromCompiler(
                    enclosing = case.host,
                    range = formal.range,
                    role = ValueRole.Formal(position),
                    executionOwner = sibling,
                    callStack = case.entered().callStack,
                )
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                source = case.argument(),
                target = target,
                kind = ValueTransferKind.SOURCE_CALL_ENTRY,
                evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.Enter(case.frame(), position)),
            ),
        )
    }

    @Test
    fun `a callback return consumes its own frame and leaves the supplying frame active`() {
        val case = ValueCallbackOracleCase()
        val frame = case.frame()
        val source =
            ValueSite.fromCompiler(
                    enclosing = case.host,
                    range = flowRange(50, 55),
                    role = ValueRole.Return,
                    executionOwner = case.callback,
                    callStack = case.entered(frame).callStack,
                )
                .flowRefined()
        val target = case.invocation.resultSite().withState(callStack = case.argument().callStack).flowRefined()
        val evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(frame))
        val transfer =
            ValueTransfer.fromCompiler(
                    source = source,
                    target = target,
                    kind = ValueTransferKind.SOURCE_CALL_RETURN,
                    evidence = evidence,
                )
                .flowRefined()
        assertEquals(listOf(case.supplyingFrame), transfer.target.callStack.frames)
        assertEquals(case.invocation.range, transfer.target.range)
        val other = ValueCallbackOracleCase(supplyStart = 10)
        val otherTarget = other.invocation.resultSite().withState(callStack = other.argument().callStack).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                source = source,
                target = otherTarget,
                kind = ValueTransferKind.SOURCE_CALL_RETURN,
                evidence = evidence,
            ),
        )
    }

    private fun entry(case: ValueCallbackOracleCase, source: ValueSite, target: ValueSite) =
        ValueTransfer.fromCompiler(
            source = source,
            target = target,
            kind = ValueTransferKind.SOURCE_CALL_ENTRY,
            evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.Enter(case.frame(), case.position)),
        )

    private fun rejectEntry(case: ValueCallbackOracleCase, source: ValueSite, target: ValueSite) {
        assertEquals(Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH), entry(case, source, target))
    }
}
