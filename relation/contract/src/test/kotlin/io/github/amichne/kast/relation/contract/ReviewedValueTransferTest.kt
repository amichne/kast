package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedValueTransferTest {
    @Test
    fun `callback result keeps the exact field beneath the output element`() {
        val fixture = ReviewedValueFixture()
        val caller = flowEndpoint("outer", 0, 100)
        val call = ValueInvocation.fromCompiler(caller, flowRange(20, 60), fixture.enclosing).flowRefined()
        val frame = ValueCallFrame.direct(call, ValueExecutionOwner.declaration(fixture.enclosing)).flowRefined()
        val stack = ValueCallStack.Empty.enter(frame).flowRefined()
        val source = fixture.source().withState(callStack = stack).flowRefined()
        val transfer = ReviewedValueTransfer.callbackResult(source, fixture.binding).flowRefined()
        assertSame(source, transfer.source)
        assertSame(fixture.binding, transfer.binding)
        assertEquals(fixture.enclosing.valueIdentity, transfer.target.enclosing.valueIdentity)
        assertEquals(flowRange(424, 459), transfer.target.range)
        assertEquals(ValueRole.ExpressionResult, transfer.target.role)
        assertEquals(ValueExecutionContext.Declaration, transfer.target.executionOwner.context)
        assertEquals(
            listOf(ValuePathSegment.Element, ValuePathSegment.Field(fixture.field)),
            transfer.target.valuePath.segments,
        )
        assertSame(fixture.field, (transfer.target.valuePath.segments.last() as ValuePathSegment.Field).field)
        assertEquals(source.callStack, transfer.target.callStack)
        assertEquals(listOf(frame), transfer.target.callStack.frames)
        assertEquals(ReviewedValueSemantics.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT, transfer.semantics)
    }

    @Test
    fun `reviewed result cannot become a compiler transfer`() {
        val transfer = ReviewedValueFixture().transfer()
        for (kind in ValueTransferKind.entries) {
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(transfer.source, transfer.target, kind),
            )
        }
    }

    @Test
    fun `callback result rejects a stale epoch or another retained owner`() {
        val fixture = ReviewedValueFixture()
        val source = fixture.source()
        val changedEpoch = ReviewedValueFixture(generation = 2)
        val changedBody = fixture.binding(body = fixture.body(430, 459)).flowRefined()
        val changedOccurrence = fixture.binding(occurrence = fixture.occurrence(423, 459)).flowRefined()
        for (binding in listOf(changedEpoch.binding, changedBody, changedOccurrence)) {
            assertEquals(
                Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
                ReviewedValueTransfer.callbackResult(source, binding),
            )
        }
    }

    @Test
    fun `callback result rejects lexical ownership and a nonreturn role`() {
        val fixture = ReviewedValueFixture()
        val lexical = fixture.source(owner = ValueExecutionOwner.declaration(fixture.enclosing))
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            ReviewedValueTransfer.callbackResult(lexical, fixture.binding),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.callbackResult(fixture.source(role = ValueRole.ExpressionResult), fixture.binding),
        )
    }

    @Test
    fun `output element consumes one access path depth without erasure`() {
        val fixture = ReviewedValueFixture()
        val full =
            ValueAccessPath.fromSegments(List(ValueAccessPath.MAX_DEPTH) { ValuePathSegment.Field(fixture.field) })
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED),
            ReviewedValueTransfer.callbackResult(fixture.source(path = full), fixture.binding),
        )
        val available = ValueAccessPath.fromSegments(full.segments.drop(1)).flowRefined()
        val admitted =
            ReviewedValueTransfer.callbackResult(fixture.source(path = available), fixture.binding).flowRefined()
        assertEquals(ValueAccessPath.MAX_DEPTH, admitted.target.valuePath.depth)
        assertEquals(available.segments, admitted.target.valuePath.segments.drop(1))
    }

    @Test
    fun `native receipt keeps reviewed output separate and charges its storage`() {
        val fixture = ReviewedValueFixture()
        val transfer = fixture.transfer()
        val observed = observed(fixture, transfer).flowRefined()
        assertTrue(observed.transfers.isEmpty())
        assertEquals(listOf(transfer), observed.reviewedTransfers)
        assertEquals(1, observed.outputCount)
        assertEquals(1, observed.receipts.single().returnedResults.value)
        assertEquals(observed.retainedBytes, observed.receipts.single().returnedBytes.value)
        val compilerOnly =
            ValueFlowStep.fromCompiler(
                    source = transfer.source,
                    transfers = emptyList(),
                    obligations = emptyList(),
                    terminal = ValueFlowTerminal.SupportedDomainExhausted,
                    domain = fixture.domain(),
                    examinedWorkUnits = RelationWorkCount.parse(1).flowRefined(),
                )
                .flowRefined()
        assertTrue(compilerOnly.reviewedTransfers.isEmpty())
        assertTrue(observed.retainedBytes > compilerOnly.retainedBytes)
        val shared = observed.shareCallableEvidence(listOf(observed))
        assertEquals(observed.reviewedTransfers, shared.reviewedTransfers)
        assertEquals(observed.receipts, shared.receipts)
    }

    @Test
    fun `incremental proof sharing retains reviewed evidence and separate source epochs`() {
        val fixture = ReviewedValueFixture()
        val first = observed(fixture, fixture.transfer()).flowRefined()
        val second = observed(fixture, fixture.transfer()).flowRefined()
        val proofs = ValueFlowProofSharing(listOf(first))
        val shared = proofs.share(second)
        assertEquals(second.reviewedTransfers, shared.reviewedTransfers)
        assertEquals(second.receipts, shared.receipts)
        assertTrue(shared.transfers.isEmpty())
        assertSame(first.source.executionOwner, shared.source.executionOwner)
        assertSame(
            first.reviewedTransfers.single().target.executionOwner,
            shared.reviewedTransfers.single().target.executionOwner,
        )
        assertEquals(
            listOf(ValuePathSegment.Element, ValuePathSegment.Field(fixture.field)),
            shared.reviewedTransfers.single().target.valuePath.segments,
        )
        val later = ReviewedValueFixture(generation = 2)
        val foreign = observed(later, later.transfer()).flowRefined()
        val foreignResult = proofs.share(foreign)
        assertEquals(foreign.reviewedTransfers, foreignResult.reviewedTransfers)
        assertSame(foreign.source.executionOwner, foreignResult.source.executionOwner)
    }

    @Test
    fun `reviewed output cannot escape detached capacity or source identity`() {
        val fixture = ReviewedValueFixture()
        val transfer = fixture.transfer()
        assertEquals(
            ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED,
            observed(fixture, transfer, domain = fixture.domain(bytes = 1)).reviewedFailure(),
        )
        assertEquals(
            ValueFlowStepFailure.SOURCE_MISMATCH,
            observed(fixture, transfer, source = fixture.source(role = ValueRole.ExpressionResult)).reviewedFailure(),
        )
        assertEquals(
            ValueFlowStepFailure.INVALID_PROGRESS,
            observed(fixture, transfer, reviewed = listOf(transfer, transfer)).reviewedFailure(),
        )
    }

    @Test
    fun `large inferred callback type consumes the detached byte grant`() {
        val fixture = ReviewedValueFixture()
        val parameterType = "kast/repro/" + "NestedType".repeat(16_384)
        val binding = fixture.binding(body = fixture.body(434, 459, parameterType)).flowRefined()
        val source = fixture.source(owner = ValueExecutionOwner.reviewedCallback(binding))
        val transfer = ReviewedValueTransfer.callbackResult(source, binding).flowRefined()
        val domain = fixture.domain(bytes = 131_072)
        assertTrue(observed(fixture, fixture.transfer(), domain = domain) is Refinement.Refined)
        assertTrue(transfer.retainedBytes >= parameterType.length.toLong() * 2L)
        assertEquals(
            ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED,
            observed(fixture, transfer, domain = domain).reviewedFailure(),
        )
    }

    @Test
    fun `long selected artifact path consumes the detached byte grant`() {
        val fixture = ReviewedValueFixture()
        val artifactUrl = "jar:///" + "dependency/".repeat(300) + "library.jar!/kotlin/collections/CollectionsKt.class"
        val file = SymbolDiscoveryFileIdentity.External(DetachedVirtualFileUrl.parse(artifactUrl).flowRefined())
        val selected = ReviewedIterableMapCallable.admit(fixture.target(file = file), fixture.position(0)).flowRefined()
        val binding = fixture.binding(selected = selected).flowRefined()
        val source = fixture.source(owner = ValueExecutionOwner.reviewedCallback(binding))
        val transfer = ReviewedValueTransfer.callbackResult(source, binding).flowRefined()
        val compact = fixture.transfer()
        val admitted = observed(fixture, compact).flowRefined()
        val extraTextUnits = artifactUrl.length - fixture.selected.target.file.stableValue.length
        val exactDomain = fixture.domain(bytes = admitted.retainedBytes)
        assertTrue(observed(fixture, compact, domain = exactDomain) is Refinement.Refined)
        assertTrue(transfer.retainedBytes - compact.retainedBytes >= extraTextUnits.toLong() * 2L)
        assertEquals(
            ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED,
            observed(fixture, transfer, domain = exactDomain).reviewedFailure(),
        )
    }

    @Test
    fun `compiler and reviewed branches share one result grant`() {
        val fixture = ReviewedValueFixture()
        val transfer = fixture.transfer()
        val otherTarget =
            ValueSite.fromCompiler(
                    enclosing = fixture.enclosing,
                    range = flowRange(435, 458),
                    role = ValueRole.Return,
                    executionOwner = transfer.source.executionOwner,
                    valuePath = transfer.source.valuePath,
                )
                .flowRefined()
        val compiler = ValueTransfer.fromCompiler(transfer.source, otherTarget, ValueTransferKind.RETURN).flowRefined()
        assertEquals(
            ValueFlowStepFailure.RESULT_LIMIT_EXCEEDED,
            observed(fixture, transfer, domain = fixture.domain(results = 1), compiler = listOf(compiler))
                .reviewedFailure(),
        )
        assertEquals(
            2,
            observed(fixture, transfer, domain = fixture.domain(results = 2), compiler = listOf(compiler))
                .flowRefined()
                .outputCount,
        )
    }

    private fun observed(
        fixture: ReviewedValueFixture,
        transfer: ReviewedValueTransfer,
        domain: RelationRequest = fixture.domain(),
        source: ValueSite = transfer.source,
        reviewed: List<ReviewedValueTransfer> = listOf(transfer),
        compiler: List<ValueTransfer> = emptyList(),
    ) =
        ValueFlowStep.fromEvidence(
            source = source,
            transfers = compiler,
            obligations = emptyList(),
            terminal = ValueFlowTerminal.SupportedDomainExhausted,
            domain = domain,
            examinedWorkUnits = RelationWorkCount.parse(1).flowRefined(),
            reviewedTransfers = reviewed,
        )
}
