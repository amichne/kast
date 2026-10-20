package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind

/** A matched direct return adds one caller fact while it keeps both original callee facts. */
class NativePacketCallerNonNullTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-caller-nonnull-jdk"

    fun testOriginalS2MatchedReturnProjectsThisNonNullToItsExactCallerReceiver() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val route = NativePacketCallerNonNullRoute(project, getOrCreateProjectBaseDir(), fixture)
        val actual = route.returned()
        NativePacketCallerNonNullFacts(project, getOrCreateProjectBaseDir(), fixture).prove(actual)
        val oldFacts = nonNull(actual)
        val read = route.read(actual.returned)
        val returned = read.transfers.single { it.kind == ValueTransferKind.SOURCE_CALL_RETURN }
        val expectedSubject =
            actual.receiver
                .withState(valuePath = ValueAccessPath.Whole, predicates = ValuePredicates.Empty)
                .nativeRefined()
        val aliases =
            returned.target.predicates.entries.filterIsInstance<ValuePredicate.NonNull>().filter {
                it.subject.occurrence == expectedSubject
            }
        assertEquals(
            "The original matched requireNotNull return needs NonNull of its actual caller receiver. " +
                "Causes=${read.obligations.map { it.cause }}. Later FeatureResponse guard acceptance=NOT_REACHED",
            1,
            aliases.size,
        )
        assertTrue(returned.target.predicates.entries.containsAll(actual.returned.predicates.entries))
        assertTrue(returned.target.predicates.entries.containsAll(oldFacts))
        assertEquals(actual.returned.valuePath, returned.target.valuePath)
        assertEquals(actual.receiver.callStack, returned.target.callStack)
        assertEquals(actual.returned.callStack.frames.last().invocation.range, returned.target.range)
        assertEquals(actual.receiver.enclosing, returned.target.enclosing)
        assertEquals(actual.receiver.executionOwner, returned.target.executionOwner)
        assertEquals(ValueRole.ExpressionResult, returned.target.role)
        val guard = returned.evidence as ValueTransferEvidence.Guarded
        assertEquals(aliases.toSet(), guard.added.entries)
        val operation = (guard.delegate as ValueSourceTransfer).operation as ValueSourceOperation.Return
        assertEquals(actual.returned.callStack.frames.last(), operation.frame)
        println("CALLER_NONNULL_RESULT original_s2_matched_alias=ASSERTED downstream_feature_guard=NOT_RUN")
    }

    fun testARealThisPredicateFromAnEmptyContextCannotEstablishTheMatchedCallerAlias() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val route = NativePacketCallerNonNullRoute(project, getOrCreateProjectBaseDir(), fixture)
        val actual = route.returned()
        val proof = NativePacketCallerNonNullFacts(project, getOrCreateProjectBaseDir(), fixture)
        proof.prove(actual)
        val current = nonNull(actual).single { it.subject.occurrence.range == actual.checked.range }
        val foreign = proof.unboundThisPredicate(route::read)
        assertEquals(current.subject.occurrence.range, foreign.subject.occurrence.range)
        assertEquals(
            current.subject.occurrence.enclosing.valueIdentity,
            foreign.subject.occurrence.enclosing.valueIdentity,
        )
        assertFalse(current.subject.occurrence.callStack == foreign.subject.occurrence.callStack)
        val predicates =
            ValuePredicates.fromCompiler(actual.returned.predicates.entries - current + foreign).nativeRefined()
        val controlled = actual.returned.withState(predicates = predicates).nativeRefined()
        val read = route.read(controlled)
        val returned = read.transfers.single { it.kind == ValueTransferKind.SOURCE_CALL_RETURN }
        val expectedSubject =
            actual.receiver
                .withState(valuePath = ValueAccessPath.Whole, predicates = ValuePredicates.Empty)
                .nativeRefined()
        assertTrue(
            returned.target.predicates.entries.filterIsInstance<ValuePredicate.NonNull>().none {
                it.subject.occurrence == expectedSubject
            }
        )
        assertEquals(controlled.predicates, returned.target.predicates)
        assertEquals(controlled.valuePath, returned.target.valuePath)
        assertEquals(actual.receiver.callStack, returned.target.callStack)
        println("CALLER_NONNULL_CONTEXT foreign_context=NO_CALLER_FACT ordinary_matched_return=PRESERVED")
    }

    private fun nonNull(actual: CallerNonNullCase): List<ValuePredicate.NonNull> {
        val facts = actual.returned.predicates.entries.filterIsInstance<ValuePredicate.NonNull>()
        assertEquals(
            setOf(actual.checked.range, actual.letResult.range),
            facts.map { it.subject.occurrence.range }.toSet(),
        )
        assertTrue(facts.all { it.subject.occurrence.valuePath == ValueAccessPath.Whole })
        assertTrue(facts.all { it.subject.occurrence.callStack == actual.returned.callStack })
        assertTrue(facts.all { it.subject.occurrence.predicates == ValuePredicates.Empty })
        return facts
    }
}
