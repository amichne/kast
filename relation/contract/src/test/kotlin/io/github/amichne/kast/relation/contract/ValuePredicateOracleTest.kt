package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValuePredicateOracleTest {
    @Test
    fun `predicate subject snapshots the exact value without recursively copying its conjunction`() {
        val initial = predicateOracleSite(20)
        val original = ValuePredicateSubject.fromCompiler(initial)
        val predicates =
            ValuePredicates.fromCompiler(listOf(ValuePredicate.NonNull.fromCompiler(original))).flowRefined()
        val guarded = initial.withState(predicates = predicates).flowRefined()
        val snapshot = ValuePredicateSubject.fromCompiler(guarded)
        assertEquals(original, snapshot)
        assertEquals(ValuePredicates.Empty, snapshot.occurrence.predicates)
        assertSame(guarded.valuePath, snapshot.occurrence.valuePath)
        assertSame(guarded.callStack, snapshot.occurrence.callStack)
        assertEquals(guarded.executionOwner, snapshot.occurrence.executionOwner)
        assertEquals(guarded.range, snapshot.occurrence.range)
        assertSame(predicates, guarded.predicates)
    }

    @Test
    fun `same resolved subtype on another value remains a distinct predicate and value state`() {
        val first = predicateOracleSite(20)
        val second = predicateOracleSite(40)
        val type = predicateOracleClass()
        val firstGuard =
            ValuePredicate.Subtype.fromCompiler(ValuePredicateSubject.fromCompiler(first), type).flowRefined()
        val secondGuard =
            ValuePredicate.Subtype.fromCompiler(ValuePredicateSubject.fromCompiler(second), type).flowRefined()
        assertSame(firstGuard.resolvedType, secondGuard.resolvedType)
        assertNotEquals(firstGuard.subject, secondGuard.subject)
        assertNotEquals(firstGuard, secondGuard)
        val firstState =
            first.withState(predicates = ValuePredicates.fromCompiler(listOf(firstGuard)).flowRefined()).flowRefined()
        val otherState =
            first.withState(predicates = ValuePredicates.fromCompiler(listOf(secondGuard)).flowRefined()).flowRefined()
        assertEquals(firstState.range, otherState.range)
        assertNotEquals(firstState.identity, otherState.identity)
    }

    @Test
    fun `same callee occurrence in two invocation contexts keeps distinct predicates`() {
        val first = contextualSite(20)
        val second = contextualSite(70)
        assertEquals(first.enclosing.valueIdentity, second.enclosing.valueIdentity)
        assertEquals(first.range, second.range)
        assertNotEquals(first.callStack, second.callStack)
        val firstGuard = ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(first))
        val secondGuard = ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(second))
        assertNotEquals(firstGuard, secondGuard)
        assertEquals(2, ValuePredicates.fromCompiler(listOf(firstGuard, secondGuard)).flowRefined().entries.size)
    }

    @Test
    fun `subtype guard needs a resolved class instead of a same named function`() {
        val subject = ValuePredicateSubject.fromCompiler(predicateOracleSite(20))
        assertEquals(
            Refinement.Rejected(ValuePredicateFailure.NOT_CLASS),
            ValuePredicate.Subtype.fromCompiler(subject, flowEndpoint("Success", 600, 650)),
        )
    }

    @Test
    fun `subtype guard rejects a foreign class basis before it changes the local subject`() {
        val subject = ValuePredicateSubject.fromCompiler(predicateOracleSite(20))
        assertEquals(
            Refinement.Rejected(ValuePredicateFailure.BASIS_MISMATCH),
            ValuePredicate.Subtype.fromCompiler(subject, predicateOracleClass(generation = 2)),
        )
        assertEquals(ValuePredicates.Empty, subject.occurrence.predicates)
    }

    @Test
    fun `foreign predicates cannot enter a local conjunction or value state`() {
        val local = predicateOracleSite(20)
        val first = ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(local))
        val foreign =
            ValuePredicate.NonNull.fromCompiler(
                ValuePredicateSubject.fromCompiler(predicateOracleSite(40, generation = 2))
            )
        val predicates = ValuePredicates.fromCompiler(listOf(foreign)).flowRefined()
        assertFalse(predicates.matchesBasis(local.basis))
        assertEquals(
            Refinement.Rejected(ValuePredicateFailure.BASIS_MISMATCH),
            ValuePredicates.fromCompiler(listOf(first, foreign)),
        )
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH),
            local.withState(predicates = predicates),
        )
    }

    @Test
    fun `sixteen distinct predicates fit and a seventeenth rejects without changing existing facts`() {
        val guards =
            List(16) { index ->
                ValuePredicate.NonNull.fromCompiler(
                    ValuePredicateSubject.fromCompiler(predicateOracleSite(10 + index * 8))
                )
            }
        val admitted = ValuePredicates.fromCompiler(guards).flowRefined()
        assertEquals(guards.toSet(), admitted.entries)
        assertEquals(16, admitted.entries.size)
        assertEquals(admitted, ValuePredicates.fromCompiler(guards + guards.first()).flowRefined())
        val extra = ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(predicateOracleSite(150)))
        assertEquals(
            Refinement.Rejected(ValuePredicateFailure.CAPACITY_EXCEEDED),
            admitted.plus(listOf(extra)),
        )
        assertEquals(guards.toSet(), admitted.entries)
    }

    private fun contextualSite(start: Int): ValueSite {
        val caller = flowEndpoint("investigate", 0, 200)
        val callee = flowEndpoint(name = "Mapper.map", start = 400, end = 500, parameters = listOf("String"))
        val invocation = ValueInvocation.fromCompiler(caller, flowRange(start, start + 20), callee).flowRefined()
        val frame = ValueCallFrame.direct(invocation, ValueExecutionOwner.declaration(callee)).flowRefined()
        return ValueSite.fromCompiler(
                enclosing = callee,
                range = flowRange(450, 455),
                role = ValueRole.ExpressionResult,
                callStack = ValueCallStack.Empty.enter(frame).flowRefined(),
            )
            .flowRefined()
    }
}

internal fun predicateOracleSite(
    start: Int,
    role: ValueRole = ValueRole.ExpressionResult,
    generation: Long = 1,
): ValueSite =
    ValueSite.fromCompiler(
            flowEndpoint(name = "inspect", start = 0, end = 300, generation = generation),
            flowRange(start, start + 5),
            role,
        )
        .flowRefined()

internal fun predicateOracleClass(generation: Long = 1): RelationEndpoint {
    val base = flowEndpoint(name = "Success", start = 600, end = 650, generation = generation)
    val evidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                file = base.file,
                rawStartInclusive = 600,
                rawEndExclusive = 650,
                rawName = "Success",
                rawQualifiedIdentity = "fixture.Success",
                kind = CompilerSymbolKind.CLASSLIKE,
                signature = CanonicalCompilerSignature.classLike("fixture.Success").flowRefined(),
            )
            .flowRefined()
    return RelationEndpoint.resolve(base.lease, base.scope, evidence).flowRefined()
}
