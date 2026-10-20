package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Assumed compiler facts isolate the explicit seed transition from native supplier discovery. */
class ValueSeedCallbackEntryTest {
    @Test
    fun `an empty seed stack enters its actual supplier and selected callback`() {
        val f = ValueCallbackOracleCase()
        val bare = f.argument(ValueCallStack.Empty)
        val path = flowPath(ValuePathSegment.Field(flowField("Response.value", 700, 720)))
        val predicates =
            ValuePredicates.fromCompiler(
                    listOf(ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(bare)))
                )
                .flowRefined()
        val source = bare.withState(valuePath = path, predicates = predicates).flowRefined()
        val target = f.entered().withState(valuePath = path, predicates = predicates).flowRefined()
        val result = enter(f, source, target).flowRefined()
        assertSame(source, result.source)
        assertSame(path, result.target.valuePath)
        assertSame(predicates, result.target.predicates)
        assertEquals(listOf(f.supplyingFrame, f.frame()), result.target.callStack.frames)
        assertEquals(ValueCallStack.Empty, source.callStack)
    }

    @Test
    fun `ordinary entry still rejects a missing active supplier`() {
        val f = ValueCallbackOracleCase()
        val result =
            ValueTransfer.fromCompiler(
                source = f.argument(ValueCallStack.Empty),
                target = f.entered(),
                kind = ValueTransferKind.SOURCE_CALL_ENTRY,
                evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.Enter(f.frame(), f.position)),
            )
        assertEquals(Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH), result)
    }

    @Test
    fun `a seed transition cannot replace an active context`() {
        val f = ValueCallbackOracleCase()
        rejected(f, f.argument(), f.entered())
    }

    @Test
    fun `a seed transition cannot omit the actual supplying frame`() {
        val f = ValueCallbackOracleCase()
        val target = f.entered(stack = ValueCallStack.Empty.enter(f.frame()).flowRefined())
        rejected(f, f.argument(ValueCallStack.Empty), target)
    }

    @Test
    fun `another real supply cannot replace the selected supplier`() {
        val f = ValueCallbackOracleCase()
        val other = ValueCallbackOracleCase(supplyStart = 10)
        val stack = ValueCallStack.Empty.enter(other.supplyingFrame).flowRefined().enter(f.frame()).flowRefined()
        rejected(f, f.argument(ValueCallStack.Empty), f.entered(stack = stack))
    }

    @Test
    fun `the same invoke identity under another full owner scope cannot enter the callback`() {
        val f = ValueCallbackOracleCase()
        val owner = changedScope(f.mapper)
        val invocation =
            ValueFunctionInvocation.fromCompiler(owner, f.invocation.range, f.invocation.callable).flowRefined()
        val source =
            ValueSite.fromCompiler(
                    enclosing = owner,
                    range = f.argument().range,
                    role = ValueRole.FunctionArgument(invocation, callbackOracleIndex()),
                )
                .flowRefined()
        assertEquals(f.invocation.identity, invocation.identity)
        rejected(f, source, f.entered())
    }

    @Test
    fun `the same callback identity under another full owner scope cannot replace the target`() {
        val f = ValueCallbackOracleCase()
        val owner = changedScope(f.host)
        val execution = callbackOracleOwner(owner, 40, 60)
        val target =
            ValueSite.fromCompiler(
                    enclosing = owner,
                    range = f.formal.range,
                    role = ValueRole.Formal(f.position),
                    executionOwner = execution,
                    callStack = f.entered().callStack,
                )
                .flowRefined()
        assertEquals(f.callback.identity, execution.identity)
        rejected(f, f.argument(ValueCallStack.Empty), target)
    }

    @Test
    fun `a seeded callback return leaves its actual supplier active`() {
        val f = ValueCallbackOracleCase()
        val entry = enter(f, f.argument(ValueCallStack.Empty), f.entered()).flowRefined()
        val source =
            ValueSite.fromCompiler(
                    enclosing = f.host,
                    range = flowRange(50, 55),
                    role = ValueRole.Return,
                    executionOwner = f.callback,
                    callStack = entry.target.callStack,
                )
                .flowRefined()
        val target = f.invocation.resultSite().withState(callStack = f.argument().callStack).flowRefined()
        val returned =
            ValueTransfer.fromCompiler(
                    source = source,
                    target = target,
                    kind = ValueTransferKind.SOURCE_CALL_RETURN,
                    evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(f.frame())),
                )
                .flowRefined()
        assertEquals(listOf(f.supplyingFrame), returned.target.callStack.frames)
    }

    private fun rejected(f: ValueCallbackOracleCase, source: ValueSite, target: ValueSite) {
        assertEquals(Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH), enter(f, source, target))
    }

    private fun changedScope(original: RelationEndpoint.Resolved): RelationEndpoint.Resolved {
        val changed =
            RelationEndpoint.resolve(
                    lease = original.lease,
                    scope =
                        (original.scope as SymbolSearchScope.Workspace).copy(libraries = SymbolLibraryPolicy.INCLUDE),
                    evidence = original.evidence,
                    constraints = original.constraints,
                )
                .flowRefined()
        assertNotEquals(original.scope, changed.scope)
        return changed
    }

    private fun enter(f: ValueCallbackOracleCase, source: ValueSite, target: ValueSite) =
        ValueTransfer.fromCompiler(
            source = source,
            target = target,
            kind = ValueTransferKind.SOURCE_CALL_ENTRY,
            evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.SeedCallbackEnter(f.frame(), f.position)),
        )
}
