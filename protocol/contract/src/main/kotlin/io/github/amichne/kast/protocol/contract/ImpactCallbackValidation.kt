package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal fun QueryCallbackFlowWireDocument.Observed.admitsFlow(basis: ImpactSemanticBasisDocument): Boolean =
    this.basis == basis &&
        when (val decoded = toContract()) {
            WireDocumentConversion.Rejected -> false
            is WireDocumentConversion.Converted ->
                (decoded.value as? QueryCallbackFlowDocument.Observed)?.admitObservedFlow(0) is Refinement.Refined
        }

internal fun ImpactCallTargetDocument.Callback.admitsCallback(
    frame: ImpactCallFrameDocument,
    basis: ImpactSemanticBasisDocument,
): Boolean {
    val invocation = (frame.invocation as? ImpactCallOccurrenceDocument.Function)?.invocation ?: return false
    val callee = frame.callee.executionOwner as? ImpactExecutionOwnerDocument.Anonymous ?: return false
    val bodySignature = flow.body.compilerEvidence.signature as? CompilerSignatureWireDocument.Function ?: return false
    val invocationSignature =
        invocation.callable.compilerEvidence.signature as? CompilerSignatureWireDocument.Function ?: return false
    val receiverCount = if (bodySignature.receiver is CompilerReceiverWireDocument.Present) 1 else 0
    return admitsSupply(frame, basis) &&
        flow.body.compilerEvidence.identity == callee.body.compilerIdentity.value &&
        flow.body.occurrence.matches(frame.callee.enclosing.file, callee.body.range) &&
        flow.invocations.any { it.sameCallbackProof(selected) } &&
        selected.occurrence.matches(frame.enclosing.file, invocation.range) &&
        selected.owner.matchesOwner(frame.enclosing, invocation.executionOwner) &&
        bodySignature.valueParameters.size + receiverCount == invocationSignature.valueParameters.size
}

private fun ImpactCallTargetDocument.Callback.admitsSupply(
    frame: ImpactCallFrameDocument,
    basis: ImpactSemanticBasisDocument,
): Boolean {
    val binding = flow.binding as? QueryCallbackBindingWireDocument.Bound ?: return false
    return flow.admitsFlow(basis) &&
        supplyingFrame.asFrame().admits(basis) &&
        binding.invocation == supplyingFrame.invocation &&
        binding.invocation.callable == supplyingFrame.callee.enclosing &&
        supplyingFrame.callee == ImpactExecutionReferenceDocument(frame.enclosing, frame.invocation.executionOwner)
}

private fun RelationOccurrenceWireDocument.matches(file: ProtocolText, range: ImpactSourceRangeDocument): Boolean =
    this.file == file.value &&
        this.range.startInclusive == range.start.value &&
        this.range.endExclusive == range.end.value

internal fun QueryCallbackBodyWireDocument.matchesOwner(
    host: ImpactDeclarationReferenceDocument,
    execution: ImpactExecutionOwnerDocument,
): Boolean =
    when (this) {
        is QueryCallbackBodyWireDocument.Named ->
            execution == ImpactExecutionOwnerDocument.Declaration &&
                callable.compilerTarget.compilerEvidence.identity == host.compilerIdentity.value &&
                callable.declaration.matches(host.file, host.range)
        is QueryCallbackBodyWireDocument.Anonymous ->
            execution is ImpactExecutionOwnerDocument.Anonymous &&
                compilerEvidence.identity == execution.body.compilerIdentity.value &&
                occurrence.matches(execution.body.file, execution.body.range)
    }

internal fun ImpactValueSiteReferenceDocument.admitsCallbackQuestion(
    flow: QueryCallbackFlowWireDocument.Observed
): Boolean =
    when (val position = role) {
        is ImpactValueRoleDocument.FunctionArgument -> {
            val binding = flow.binding as? QueryCallbackBindingWireDocument.Bound
            val frame = callStack.values.lastOrNull()
            binding != null &&
                frame != null &&
                enclosing == frame.callee.enclosing &&
                position.invocation.executionOwner == frame.callee.executionOwner &&
                binding.invocation == (frame.invocation as? ImpactCallOccurrenceDocument.Declared)?.invocation
        }
        ImpactValueRoleDocument.Return ->
            callStack.values.lastOrNull()?.let { frame ->
                enclosing == frame.callee.enclosing &&
                    executionOwner == frame.callee.executionOwner &&
                    (frame.target as? ImpactCallTargetDocument.Callback)?.flow?.sameCallbackQuestion(flow) == true
            } == true
        is ImpactValueRoleDocument.Argument,
        is ImpactValueRoleDocument.Receiver,
        is ImpactValueRoleDocument.Formal,
        ImpactValueRoleDocument.ExpressionResult,
        ImpactValueRoleDocument.LocalBinding,
        ImpactValueRoleDocument.LocalRead,
        ImpactValueRoleDocument.PropertyAssignment -> false
    }
