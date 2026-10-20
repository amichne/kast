package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ValueStringDerivationContextOracleTest {
    @Test
    fun `a different declaration cannot receive the same range as a string derivation`() {
        val case = StringDerivationOracleCase()
        val other = flowEndpoint("other", 0, 250)
        val target =
            ValueSite.fromCompiler(
                    enclosing = other,
                    range = case.template,
                    role = ValueRole.ExpressionResult,
                    executionOwner = StringDerivationOracleCase.execution(other, 10, 200),
                    callStack = case.stack,
                    predicates = case.predicates,
                )
                .flowRefined()
        case.reject(target = target)
    }

    @Test
    fun `a changed scope with identical declaration metadata cannot receive the derivation`() {
        val case = StringDerivationOracleCase()
        val original = case.owner as RelationEndpoint.Resolved
        val scoped =
            RelationEndpoint.resolve(
                    original.lease,
                    SymbolSearchScope.Workspace(
                        SymbolSourceKindPolicy.PRODUCTION_ONLY,
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                        SymbolLibraryPolicy.EXCLUDE,
                    ),
                    original.evidence,
                )
                .flowRefined()
        val execution = StringDerivationOracleCase.execution(scoped, 10, 200)
        val target =
            ValueSite.fromCompiler(
                    enclosing = scoped,
                    range = case.template,
                    role = ValueRole.ExpressionResult,
                    executionOwner = execution,
                    callStack = case.stack,
                    predicates = case.predicates,
                )
                .flowRefined()
        assertEquals(original.compilerIdentity, scoped.compilerIdentity)
        assertEquals(original.range, scoped.range)
        case.reject(target = target)
    }

    @Test
    fun `a different anonymous body under the same host cannot receive the derivation`() {
        val case = StringDerivationOracleCase()
        val target =
            ValueSite.fromCompiler(
                    enclosing = case.owner,
                    range = case.template,
                    role = ValueRole.ExpressionResult,
                    executionOwner = StringDerivationOracleCase.execution(case.owner, 15, 190),
                    callStack = case.stack,
                    predicates = case.predicates,
                )
                .flowRefined()
        case.reject(target = target)
    }

    @Test
    fun `a fresh basis cannot receive an older interpolation transfer`() {
        val case = StringDerivationOracleCase()
        val other = flowEndpoint("interpolate", start = 0, end = 250, generation = 2)
        val target = ValueSite.fromCompiler(other, case.template, ValueRole.ExpressionResult).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.BASIS_MISMATCH),
            ValueTransfer.fromCompiler(
                source = case.source,
                target = target,
                kind = ValueTransferKind.DERIVATION,
                evidence = case.evidence,
            ),
        )
    }

    @Test
    fun `a missing frame or another same-callee invocation cannot receive the derivation`() {
        val case = StringDerivationOracleCase()
        case.reject(target = case.target.withState(callStack = ValueCallStack.Empty).flowRefined())
        val other = ValueCallStack.Empty.enter(case.otherFrame).flowRefined()
        case.reject(target = case.target.withState(callStack = other).flowRefined())
    }

    @Test
    fun `derivation cannot erase a predicate or invent an unrelated predicate`() {
        val case = StringDerivationOracleCase()
        case.reject(target = case.target.withState(predicates = ValuePredicates.Empty).flowRefined())
        val other = case.site(flowRange(70, 75), ValueRole.LocalRead)
        val added = ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(other))
        val predicates = case.predicates.plus(listOf(added)).flowRefined()
        case.reject(target = case.target.withState(predicates = predicates).flowRefined())
    }
}
