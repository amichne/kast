package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal class ImpactSeedCallbackReturnCase {
    val callback = CallbackIdentityFixture()
    val frame = callback.frame
    val operation = ImpactSourceOperationDocument.SeedCallbackReturn(frame)
    private val path = bounded<ImpactValuePathSegmentDocument>(listOf(ImpactValuePathSegmentDocument.AnyElement))
    private val subject =
        ImpactPredicateSubjectDocument(
            frame.callee.enclosing,
            range(75, 79),
            ImpactValueRoleDocument.Return,
            frame.callee.executionOwner,
            path,
        )
    val source =
        ImpactValueSiteReferenceDocument(
            subject.enclosing,
            subject.range,
            subject.role,
            subject.executionOwner,
            path,
            predicates = bounded(listOf(ImpactValuePredicateDocument.NonNull(subject))),
        )
    val target =
        ImpactValueSiteReferenceDocument(
            frame.enclosing,
            frame.invocation.range,
            ImpactValueRoleDocument.ExpressionResult,
            frame.invocation.executionOwner,
            path,
            bounded(listOf(callback.target.supplyingFrame.asFrame())),
            source.predicates,
        )
    val transfer =
        ImpactCompilerTransferDocument(
            source,
            target,
            ImpactTransferKindDocument.SOURCE_CALL_RETURN,
            ImpactTransferEvidenceDocument.Source(operation),
        )
    val qualified =
        callback.flow.copy(
            invocations = emptyList(),
            obligations = listOf(QueryCallbackFlowCauseDocument.WORK_LIMIT_REACHED),
            scan = QueryCallbackInvocationScanDocument.INCOMPLETE,
        )

    fun withFrame(changed: ImpactCallFrameDocument) =
        transfer.copy(evidence = ImpactTransferEvidenceDocument.Source(operation.copy(frame = changed)))
}

private fun range(start: Int, end: Int) =
    ImpactSourceRangeDocument(ProtocolOffset.parse(start).value(), ProtocolOffset.parse(end).value())

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
