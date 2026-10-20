package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter

/** Pure policy characterization. Assumed compiler facts establish no native resolution or runtime performance. */
class ValueReviewedReturnSharingTest {
    @Test
    fun `reviewed return sharing conserves nested predicate proof without more retained policy bytes`(
        reporter: TestReporter
    ) {
        val f = ValueReviewedSharingCase(ReviewedValueModel.Let)
        val before = returnStep(f)
        val original = before.applications.single() as ValueModelApplication.ScopeCallbackReturn
        val subject = before.source.predicates.entries.filterIsInstance<ValuePredicate.NonNull>().single().subject
        assertSame(original.frame, before.source.callStack.frames.last())
        assertSame(original.frame, subject.occurrence.callStack.frames.last())
        assertNotSame(f.prior.applications.single().invocation.callable, original.invocation.callable)

        val after = before.shareCallableEvidence(listOf(f.prior))
        val shared = after.applications.single() as ValueModelApplication.ScopeCallbackReturn
        reporter.publishEntry("return.before.policyBytes", before.retainedBytes.toString())
        reporter.publishEntry("return.after.policyBytes", after.retainedBytes.toString())
        reporter.publishEntry("return.before.frame", System.identityHashCode(original.frame).toString())
        reporter.publishEntry("return.after.frame", System.identityHashCode(shared.frame).toString())
        assertEquals(before.source, after.source)
        assertEquals(original, shared)
        assertEquals(original.binding, shared.binding)
        assertEquals(original.frame.target, shared.frame.target)
        assertSame(before.source.predicates, after.source.predicates)
        assertSame(subject, after.source.predicates.entries.filterIsInstance<ValuePredicate.NonNull>().single().subject)
        assertSame(after.source, shared.source)
        assertSame(shared.frame, after.source.callStack.frames.last())
        assertSame(before.domain, after.domain)
        assertSame(before.receipts, after.receipts)
        assertEquals(before.obligations, after.obligations)
        assertEquals(before.examinedWorkUnits, after.examinedWorkUnits)
        assertEquals(before.terminal, after.terminal)
        assertTrue(after.retainedBytes <= before.retainedBytes)
    }

    private fun returnStep(f: ValueReviewedSharingCase): ValueFlowStep {
        val input = f.fresh.applications.single() as ValueModelApplication.ScopeCallbackInput
        val tested =
            ValueSite.fromCompiler(
                    enclosing = input.frame.callee.enclosing,
                    range = flowRange(54, 56),
                    role = ValueRole.LocalRead,
                    executionOwner = input.frame.callee,
                    valuePath = f.path,
                    callStack = input.target.callStack,
                )
                .flowRefined()
        val predicates =
            input.target.predicates
                .plus(listOf(ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(tested))))
                .flowRefined()
        val source =
            ValueSite.fromCompiler(
                    enclosing = input.frame.callee.enclosing,
                    range = flowRange(56, 57),
                    role = ValueRole.Return,
                    executionOwner = input.frame.callee,
                    valuePath = f.path,
                    callStack = input.target.callStack,
                    predicates = predicates,
                )
                .flowRefined()
        return ValueFlowStep.fromCompiler(
                source = source,
                transfers = emptyList(),
                obligations = listOf(ValueFlowObligation(source, ValueFlowUnsupportedCause.EXTERNAL_CALL)),
                terminal = ValueFlowTerminal.Unresolved,
                domain = f.fresh.domain,
                examinedWorkUnits = RelationWorkCount.parse(2).flowRefined(),
                applications =
                    listOf(ValueModelApplication.ScopeCallbackReturn.leave(source, input.frame).flowRefined()),
            )
            .flowRefined()
    }
}
