package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ValueSeedCallbackReturnProofOracleTest {
    @Test
    fun `the same callback identity cannot substitute another full source scope`() {
        val case = SeedCallbackReturnOracleCase()
        val owner = seedReturnChangedScope(case.callback.host)
        val execution = callbackOracleOwner(owner, 40, 60)
        assertEquals(case.callback.callback.identity, execution.identity)
        case.rejected(source = case.returned(execution))
    }

    @Test
    fun `the same invoke identity cannot substitute another full result scope`() {
        val case = SeedCallbackReturnOracleCase()
        val f = case.callback
        val owner = seedReturnChangedScope(f.mapper)
        val invocation =
            ValueFunctionInvocation.fromCompiler(owner, f.invocation.range, f.invocation.callable).flowRefined()
        assertEquals(f.invocation.identity, invocation.identity)
        case.rejected(target = case.result(invocation))
    }

    @Test
    fun `another genuine anonymous body cannot return through the selected frame`() {
        val case = SeedCallbackReturnOracleCase()
        val otherBody = callbackOracleOwner(case.callback.host, 45, 65)
        case.rejected(source = case.returned(otherBody))
    }

    @Test
    fun `a foreign result basis keeps the exact basis rejection`() {
        val case = SeedCallbackReturnOracleCase()
        val foreign = SeedCallbackReturnOracleCase(generation = 2)
        case.rejected(target = foreign.target, failure = ValueTransferFailure.BASIS_MISMATCH)
    }

    @Test
    fun `a foreign callback proof cannot authorize two local sites`() {
        val case = SeedCallbackReturnOracleCase()
        val foreign = SeedCallbackReturnOracleCase(generation = 2)
        case.rejected(frame = foreign.frame)
    }

    @Test
    fun `an ordinary expression cannot masquerade as a normal callback return`() {
        val case = SeedCallbackReturnOracleCase()
        val source =
            ValueSite.fromCompiler(
                    enclosing = case.callback.host,
                    range = case.source.range,
                    role = ValueRole.ExpressionResult,
                    executionOwner = case.callback.callback,
                    valuePath = case.path,
                    predicates = case.predicates,
                )
                .flowRefined()
        case.rejected(source = source)
    }

    @Test
    fun `the target needs the exact expression result role`() {
        val case = SeedCallbackReturnOracleCase()
        val target =
            ValueSite.fromCompiler(
                    enclosing = case.target.enclosing,
                    range = case.target.range,
                    role = ValueRole.Return,
                    executionOwner = case.target.executionOwner,
                    valuePath = case.path,
                    callStack = case.expectedStack,
                    predicates = case.predicates,
                )
                .flowRefined()
        case.rejected(target = target)
    }

    @Test
    fun `the target cannot erase the returned field path`() {
        val case = SeedCallbackReturnOracleCase()
        case.rejected(target = case.target.withState(valuePath = ValueAccessPath.Whole).flowRefined())
    }

    @Test
    fun `the target cannot erase the scoped predicate`() {
        val case = SeedCallbackReturnOracleCase()
        case.rejected(target = case.target.withState(predicates = ValuePredicates.Empty).flowRefined())
    }

    @Test
    fun `the callback seed operation cannot authorize another transfer kind`() {
        val case = SeedCallbackReturnOracleCase()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                source = case.source,
                target = case.target,
                kind = ValueTransferKind.BRANCH_ALTERNATIVE,
                evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.SeedCallbackReturn(case.frame)),
            ),
        )
    }
}
