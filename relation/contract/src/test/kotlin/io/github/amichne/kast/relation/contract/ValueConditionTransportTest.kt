package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueConditionTransportTest {
    @Test
    fun `reviewed callback result retains condition origin across its execution boundary`() {
        val fixture = ReviewedValueFixture()
        val returned = fixture.source()
        val receiver =
            ValueSite.fromCompiler(
                    fixture.enclosing,
                    returned.range,
                    ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)),
                    executionOwner = returned.executionOwner,
                    valuePath = returned.valuePath,
                )
                .flowRefined()
        val conditions = conditions(receiver)
        val source = returned.withState(conditions = conditions).flowRefined()
        val transfer = ReviewedValueTransfer.callbackResult(source, fixture.binding).flowRefined()
        assertSame(conditions, transfer.target.conditions)
        assertEquals(receiver.executionOwner, transfer.target.conditions.facts.single().origin.execution)
        assertEquals(source.conditions, transfer.target.withState().flowRefined().conditions)
        assertEquals(ValueExecutionContext.Declaration, transfer.target.executionOwner.context)
    }

    @Test
    fun `local read resume rejects an emitted identity that erased the conditions`() {
        val owner = flowEndpoint("receiver", 0, 200)
        val receiver =
            ValueSite.fromCompiler(
                    owner,
                    flowRange(10, 20),
                    ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)),
                )
                .flowRefined()
        val conditions = conditions(receiver)
        val binding =
            ValueSite.fromCompiler(
                    owner,
                    flowRange(25, 45),
                    ValueRole.LocalBinding,
                    conditions = conditions,
                )
                .flowRefined()
        val erased = ValueSite.fromCompiler(owner, flowRange(50, 60), ValueRole.LocalRead).flowRefined()
        val read = erased.withState(conditions = conditions).flowRefined()
        val remainder =
            LocalBindingReadRemainder.admit(
                    source = binding,
                    boundary = RelationSearchBoundary.RETAINED_SUBJECT,
                    consumed = emptySet(),
                    emitted = setOf(read.identity),
                )
                .flowRefined()
        assertEquals(setOf(read.identity), remainder.emitted)
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.BASIS_MISMATCH),
            LocalBindingReadRemainder.admit(
                source = binding,
                boundary = RelationSearchBoundary.RETAINED_SUBJECT,
                consumed = emptySet(),
                emitted = setOf(erased.identity),
            ),
        )
    }

    @Test
    fun `readmission keeps same basis conditions and rejects stale facts rather than erasing them`() {
        val fixture = CallbackInvocationFlowFixture()
        val owner = MovingLiveReadAuthorityFixture(fixture.root)
        val prior = owner.admit()
        val endpoint = RelationEndpoint.resolve(prior, fixture.target.scope, fixture.target.evidence).flowRefined()
        val receiver =
            ValueSite.fromCompiler(
                    endpoint,
                    endpoint.range,
                    ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)),
                )
                .flowRefined()
        val source = receiver.withState(conditions = conditions(receiver)).flowRefined()
        val same = CallbackEndpointReadmissions.fromCompiler(prior, mapOf(endpoint to endpoint)).flowRefined()
        assertSame(source.conditions, CallbackEvidenceReadmission(same).site(source).flowRefined().conditions)
        val current = owner.advance()
        val restored = RelationEndpoint.resolve(current, fixture.target.scope, fixture.target.evidence).flowRefined()
        val changed = CallbackEndpointReadmissions.fromCompiler(current, mapOf(endpoint to restored)).flowRefined()
        assertEquals(
            Refinement.Rejected(CallbackSummaryReadmissionFailure.Site(ValueSiteFailure.STATE_BASIS_MISMATCH)),
            CallbackEvidenceReadmission(changed).site(source),
        )
    }

    private fun conditions(source: ValueSite): ValueConditions =
        ValueConditions.Unconditional.requiring(
                ValueCondition.StableReceiver.fromCompiler(
                        source = source,
                        useRange = source.range,
                        kind = ValueReceiverKind.EXTENSION,
                        originalType = CanonicalCompilerType.fromCompiler("fixture.Choice").flowRefined(),
                        refinedType = CanonicalCompilerType.fromCompiler("fixture.Selected").flowRefined(),
                    )
                    .flowRefined()
            )
            .flowRefined()
}
