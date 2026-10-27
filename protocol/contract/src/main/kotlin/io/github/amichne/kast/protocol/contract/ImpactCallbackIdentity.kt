package io.github.amichne.kast.protocol.contract

/** Callback identity keeps dispatch qualifications while observations remain available in the encoded documents. */
internal fun ImpactExecutionOwnerDocument.hasSameExecutionIdentity(other: ImpactExecutionOwnerDocument): Boolean =
    when (this) {
        ImpactExecutionOwnerDocument.Declaration -> other == ImpactExecutionOwnerDocument.Declaration
        is ImpactExecutionOwnerDocument.ReviewedCallback -> other == this
        is ImpactExecutionOwnerDocument.DeclaredCallback -> other == this
        is ImpactExecutionOwnerDocument.Callback ->
            other is ImpactExecutionOwnerDocument.Callback &&
                body == other.body &&
                invocations.hasSameCaptures(other.invocations) &&
                selection.hasSameSelectionIdentity(other.selection)
    }

internal fun ImpactExecutionReferenceDocument.hasSameExecutionIdentity(
    other: ImpactExecutionReferenceDocument
): Boolean = enclosing == other.enclosing && executionOwner.hasSameExecutionIdentity(other.executionOwner)

internal fun ImpactInvocationReferenceDocument.hasSameInvocationIdentity(
    other: ImpactInvocationReferenceDocument
): Boolean =
    range == other.range && callable == other.callable && executionOwner.hasSameExecutionIdentity(other.executionOwner)

internal fun ImpactInvocationReferenceDocument.hasSameInvocationIdentity(other: ImpactCallOccurrenceDocument): Boolean =
    when (other) {
        is ImpactCallOccurrenceDocument.Declared -> hasSameInvocationIdentity(other.invocation)
    }

internal fun ImpactCallOccurrenceDocument.hasSameInvocationIdentity(other: ImpactCallOccurrenceDocument): Boolean =
    when (this) {
        is ImpactCallOccurrenceDocument.Declared ->
            other is ImpactCallOccurrenceDocument.Declared && invocation.hasSameInvocationIdentity(other.invocation)
    }

internal fun BoundedProtocolList<ImpactCaptureInvocationDocument>.hasSameCaptures(
    other: BoundedProtocolList<ImpactCaptureInvocationDocument>
): Boolean =
    values.size == other.values.size &&
        values.zip(other.values).all { (left, right) -> left.hasSameCaptureIdentity(right) }

internal fun ImpactCaptureInvocationDocument.hasSameCaptureIdentity(other: ImpactCaptureInvocationDocument): Boolean =
    body == other.body &&
        enclosing == other.enclosing &&
        supply.hasSameInvocationIdentity(other.supply) &&
        supplyOwner == other.supplyOwner &&
        parameter == other.parameter &&
        position == other.position &&
        invocation == other.invocation &&
        invocationOwner == other.invocationOwner &&
        supplyTarget.hasSameTargetIdentity(other.supplyTarget) &&
        forwardings.values.hasSameForwardings(other.forwardings.values)

private fun ImpactCallbackSelectionDocument.hasSameSelectionIdentity(other: ImpactCallbackSelectionDocument): Boolean =
    when (this) {
        ImpactCallbackSelectionDocument.Unique -> other == ImpactCallbackSelectionDocument.Unique
        is ImpactCallbackSelectionDocument.Exact ->
            other is ImpactCallbackSelectionDocument.Exact && invocation.hasSameCaptureIdentity(other.invocation)
    }

private fun List<ImpactCallbackForwardingDocument>.hasSameForwardings(
    other: List<ImpactCallbackForwardingDocument>
): Boolean = size == other.size && zip(other).all { (left, right) -> left.hasSameForwardingIdentity(right) }

private fun ImpactCallbackForwardingDocument.hasSameForwardingIdentity(
    other: ImpactCallbackForwardingDocument
): Boolean =
    sourceParameter == other.sourceParameter &&
        sourcePosition == other.sourcePosition &&
        argument == other.argument &&
        targetFrame.hasSameFrameIdentity(other.targetFrame) &&
        parameter == other.parameter &&
        position == other.position &&
        callableTransfers.values.hasSameCallableTransfers(other.callableTransfers.values)

private fun List<ImpactCompilerTransferDocument>.hasSameCallableTransfers(
    other: List<ImpactCompilerTransferDocument>
): Boolean =
    size == other.size &&
        zip(other).all { (left, right) ->
            left.kind == right.kind &&
                left.evidence == right.evidence &&
                left.source.hasSameValueIdentity(right.source) &&
                left.target.hasSameValueIdentity(right.target)
        }
