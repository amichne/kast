package io.github.amichne.kast.protocol.contract

internal fun ImpactCallTargetDocument.ReviewedCallback.admitsReviewedCallback(
    frame: ImpactCallFrameDocument,
    basis: ImpactSemanticBasisDocument,
): Boolean =
    binding.model is ImpactReviewedValueModelDocument.Let &&
        binding.admits(basis) &&
        binding.invocation == frame.invocation &&
        binding.enclosing.declaration == frame.enclosing &&
        hasFormalProof(frame, basis) &&
        hasLiteralProof(frame, basis)

private fun ImpactCallTargetDocument.ReviewedCallback.hasFormalProof(
    frame: ImpactCallFrameDocument,
    basis: ImpactSemanticBasisDocument,
): Boolean =
    formalCallable.admits(basis) &&
        formal.admits() &&
        formalCallable == binding.enclosing &&
        formalExecution.enclosing == formalCallable &&
        formal.callable == formalCallable.declaration &&
        formalExecution.matches(formal.executionOwner) &&
        frame.callee.enclosing == formal.callable &&
        frame.callee.executionOwner == formal.executionOwner &&
        formal.index.value == 0

private fun ImpactCallTargetDocument.ReviewedCallback.hasLiteralProof(
    frame: ImpactCallFrameDocument,
    basis: ImpactSemanticBasisDocument,
): Boolean {
    val execution = formalExecution as? ImpactModelExecutionDocument.Anonymous ?: return false
    val signature = execution.body.compilerEvidence.signature as? CompilerSignatureWireDocument.Function ?: return false
    return signature.receiver == CompilerReceiverWireDocument.Absent &&
        signature.valueParameters.size == 1 &&
        argument.file.value == execution.body.occurrence.file &&
        argument.file == formal.callable.file &&
        argument.file.value.startsWith(basis.root.value.trimEnd('/') + "/") &&
        argument.range.start.value == execution.body.occurrence.range.startInclusive &&
        argument.range.end.value == execution.body.occurrence.range.endExclusive &&
        frame.invocation.range.containsReviewed(argument.range)
}

internal fun ImpactValueModelApplicationDocument.ScopeCallbackInput.admitsScopeInput(
    basis: ImpactSemanticBasisDocument
): Boolean {
    val proof = frame.target as? ImpactCallTargetDocument.ReviewedCallback ?: return false
    return hasReviewedContext(frame, proof, basis) &&
        hasInputSites(frame, proof) &&
        source.callStack.values.size < MAX_VALUE_CALL_FRAMES &&
        source.callStack.values.none { it.hasSameFrameIdentity(frame) } &&
        (source.callStack.values + frame).hasSameFrames(target.callStack.values)
}

private fun ImpactValueModelApplicationDocument.ScopeCallbackInput.hasInputSites(
    frame: ImpactCallFrameDocument,
    proof: ImpactCallTargetDocument.ReviewedCallback,
): Boolean {
    val call = (binding.invocation as? ImpactCallOccurrenceDocument.Declared)?.invocation ?: return false
    val receiver = source.role as? ImpactValueRoleDocument.Receiver ?: return false
    val position = ImpactFormalPositionDocument.Parameter(proof.formal)
    return receiver.kind == ImpactReceiverKindDocument.EXTENSION &&
        receiver.invocation == call &&
        source.enclosing == frame.enclosing &&
        source.executionOwner == frame.invocation.executionOwner &&
        target.isIn(frame.callee) &&
        target.role == ImpactValueRoleDocument.Formal(position) &&
        position.admits(target)
}

internal fun ImpactValueModelApplicationDocument.ScopeCallbackReturn.admitsScopeReturn(
    basis: ImpactSemanticBasisDocument
): Boolean {
    val proof = frame.target as? ImpactCallTargetDocument.ReviewedCallback ?: return false
    return hasReviewedContext(frame, proof, basis) &&
        source.role == ImpactValueRoleDocument.Return &&
        source.isIn(frame.callee) &&
        target.isResultOf(frame) &&
        source.callStack.values.lastOrNull()?.hasSameFrameIdentity(frame) == true &&
        source.callStack.values.dropLast(1).hasSameFrames(target.callStack.values)
}

private fun ImpactValueModelApplicationDocument.hasReviewedContext(
    frame: ImpactCallFrameDocument,
    proof: ImpactCallTargetDocument.ReviewedCallback,
    basis: ImpactSemanticBasisDocument,
): Boolean =
    frame.admits(basis) &&
        binding.hasSameReviewedBinding(proof.binding) &&
        source.valuePath == target.valuePath &&
        source.predicates.hasSamePredicateIdentity(target.predicates)

private fun ImpactSourceRangeDocument.containsReviewed(other: ImpactSourceRangeDocument): Boolean =
    start.value <= other.start.value && other.start.value < other.end.value && other.end.value <= end.value
