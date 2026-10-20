package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter

class ValueReviewedEvidenceSharingTest {
    @Test
    fun `let sharing conserves semantic proof qualifications and native receipts`(reporter: TestReporter) {
        characterize(ValueReviewedSharingCase(ReviewedValueModel.Let), reporter)
    }

    @Test
    fun `first-or-null sharing conserves semantic proof qualifications and native receipts`(reporter: TestReporter) {
        characterize(ValueReviewedSharingCase(ReviewedValueModel.FirstOrNull), reporter)
    }

    @Test
    fun `the same callable identity with another exact scope stays distinct`(reporter: TestReporter) {
        val f = ValueReviewedSharingCase(ReviewedValueModel.FirstOrNull)
        val original = f.endpoint() as RelationEndpoint.Resolved
        val scope = (original.scope as SymbolSearchScope.Workspace).copy(libraries = SymbolLibraryPolicy.EXCLUDE)
        val changed =
            RelationEndpoint.resolve(
                    lease = original.lease,
                    scope = scope,
                    evidence = original.evidence,
                    constraints = original.constraints,
                )
                .flowRefined()
        val before = f.observation(callable = changed)
        val after = before.shareCallableEvidence(listOf(f.prior))
        record("scope", before, after, reporter)
        assertEquals(original.valueIdentity, changed.valueIdentity)
        assertNotEquals(original, changed)
        assertSame(changed, receiverCall(after).callable)
        assertSame(changed, after.applications.single().invocation.callable)
        assertNotSame(receiverCall(f.prior).callable, receiverCall(after).callable)
        assertEquals(before.applications.single().binding, after.applications.single().binding)
        assertConserved(before, after)
    }

    @Test
    fun `another artifact module cannot borrow an older reviewed binding`(reporter: TestReporter) {
        val f = ValueReviewedSharingCase(ReviewedValueModel.Let)
        val artifact =
            f.metadata.artifact.copy(moduleName = LibraryModelModuleName.parse("other-stdlib-module").flowRefined())
        val before = f.observation(artifact = artifact)
        val after = before.shareCallableEvidence(listOf(f.prior))
        record("artifact", before, after, reporter)
        assertNotEquals(f.prior.applications.single().binding, before.applications.single().binding)
        assertEquals(artifact, after.applications.single().binding.artifact)
        assertEquals("other-stdlib-module", after.applications.single().binding.artifact.moduleName.value)
        assertNotSame(f.prior.applications.single().binding, after.applications.single().binding)
        assertConserved(before, after)
    }

    @Test
    fun `let sharing keeps one physical source and complete callable proof`(reporter: TestReporter) {
        assertCoherent(ValueReviewedSharingCase(ReviewedValueModel.Let), reporter)
    }

    @Test
    fun `first-or-null sharing keeps one physical source and complete callable proof`(reporter: TestReporter) {
        assertCoherent(ValueReviewedSharingCase(ReviewedValueModel.FirstOrNull), reporter)
    }

    private fun characterize(f: ValueReviewedSharingCase, reporter: TestReporter) {
        val before = f.fresh
        val priorCall = receiverCall(f.prior)
        assertEquals(priorCall, receiverCall(before))
        assertEquals(priorCall.callable, receiverCall(before).callable)
        assertNotSame(priorCall, receiverCall(before))
        assertNotSame(priorCall.callable, receiverCall(before).callable)
        assertNotSame(priorCall.signature, receiverCall(before).signature)
        assertNotEquals(f.prior.source.identity, before.source.identity)
        val after = before.shareCallableEvidence(listOf(f.prior))
        record(f.model.reference.rule.value, before, after, reporter)
        assertSame(priorCall.callable, receiverCall(after).callable)
        assertSame(priorCall.signature, receiverCall(after).signature)
        assertConserved(before, after)
    }

    private fun assertConserved(before: ValueFlowStep, after: ValueFlowStep) {
        val expected = before.applications.single()
        val actual = after.applications.single()
        assertEquals(before.source, after.source)
        assertSame(before.source.enclosing, after.source.enclosing)
        assertSame(before.source.executionOwner, after.source.executionOwner)
        assertSame(before.source.valuePath, after.source.valuePath)
        assertSame(before.source.callStack, after.source.callStack)
        assertSame(before.source.predicates, after.source.predicates)
        assertEquals(expected, actual)
        assertEquals(expected.binding, actual.binding)
        assertEquals(expected.binding.artifact, actual.binding.artifact)
        assertEquals(expected.reference, actual.reference)
        assertEquals(expected.target.valuePath, actual.target.valuePath)
        assertEquals(expected.target.predicates, actual.target.predicates)
        assertEquals(expected.target.callStack, actual.target.callStack)
        assertEquals(before.obligations, after.obligations)
        assertEquals(
            listOf(ValueFlowUnsupportedCause.EXTERNAL_CALL, ValueFlowUnsupportedCause.OUTSIDE_DOMAIN),
            after.obligations.map { it.cause },
        )
        assertSame(before.domain, after.domain)
        assertSame(before.receipts, after.receipts)
        assertEquals(1, after.receipts.single().returnedResults.value)
        assertEquals(2L, after.examinedWorkUnits.value)
        assertEquals(before.terminal, after.terminal)
    }

    private fun assertCoherent(f: ValueReviewedSharingCase, reporter: TestReporter) {
        val before = f.fresh
        val after = before.shareCallableEvidence(listOf(f.prior))
        record("physical-" + f.model.reference.rule.value, before, after, reporter)
        val application = after.applications.single()
        assertSame(after.source, application.source)
        after.obligations.forEach { assertSame(after.source, it.site) }
        assertSame(receiverCall(after), application.invocation)
        assertSame(receiverCall(f.prior).callable, application.invocation.callable)
        assertSame(receiverCall(f.prior).signature, application.invocation.signature)
        assertNotSame(f.prior.source, after.source)
        assertTrue(after.retainedBytes <= before.retainedBytes)
        when (application) {
            is ValueModelApplication.ScopeCallbackInput -> {
                val target = application.frame.target as ValueReviewedCallbackTarget
                assertSame(application.binding, target.binding)
                assertSame(application.invocation, application.frame.invocation)
                assertSame(application.frame, application.target.callStack.frames.last())
            }
            is ValueModelApplication.CollectionSelection ->
                assertSame(application.invocation, (application.condition.receiver.role as ValueRole.Receiver).call)
            is ValueModelApplication.ScopeCallbackReturn -> error("The fixture admits input applications only")
            is ValueModelApplication.CollectionInsertion -> error("The fixture admits receiver applications only")
        }
        assertConserved(before, after)
    }

    private fun record(label: String, before: ValueFlowStep, after: ValueFlowStep, reporter: TestReporter) {
        reporter.publishEntry(label + ".before", physical(before))
        reporter.publishEntry(label + ".after", physical(after))
    }

    private fun physical(step: ValueFlowStep): String {
        val application = step.applications.single()
        return "source=${System.identityHashCode(step.source)} " +
            "application=${System.identityHashCode(application)} " +
            "applicationSource=${System.identityHashCode(application.source)} " +
            "callable=${System.identityHashCode(receiverCall(step).callable)} " +
            "signature=${System.identityHashCode(receiverCall(step).signature)} " +
            "bindingCallable=${System.identityHashCode(application.invocation.callable)} " +
            "bindingSignature=${System.identityHashCode(application.invocation.signature)} " +
            "policyBytes=${step.retainedBytes}"
    }

    private fun receiverCall(step: ValueFlowStep): ValueInvocation = (step.source.role as ValueRole.Receiver).call
}
