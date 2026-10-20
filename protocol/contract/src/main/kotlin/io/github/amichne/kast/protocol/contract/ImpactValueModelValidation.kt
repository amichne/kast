package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Output structure preserves reviewed provenance. This decision never issues a native model capability. */
internal fun ImpactValueModelApplicationDocument.admitsValueModel(basis: ImpactSemanticBasisDocument): Boolean {
    if (!source.isOnBasis(basis) || !target.isOnBasis(basis) || !binding.admits(basis)) return false
    return when (this) {
        is ImpactValueModelApplicationDocument.CollectionSelection -> admitsCollectionSelection()
        is ImpactValueModelApplicationDocument.CollectionInsertion -> admitsCollectionInsertion()
        is ImpactValueModelApplicationDocument.ScopeCallbackInput -> admitsScopeInput(basis)
        is ImpactValueModelApplicationDocument.ScopeCallbackReturn -> admitsScopeReturn(basis)
    }
}

private fun ImpactValueModelApplicationDocument.CollectionInsertion.admitsCollectionInsertion(): Boolean {
    val call = (binding.invocation as? ImpactCallOccurrenceDocument.Declared)?.invocation ?: return false
    val argument = source.role as? ImpactValueRoleDocument.Argument ?: return false
    return binding.model is ImpactReviewedValueModelDocument.SingleElementListOf &&
        argument.index.value == 0 &&
        argument.invocation == call &&
        source.range.start.value >= call.range.start.value &&
        source.range.end.value <= call.range.end.value &&
        index.value == 0 &&
        hasConsistentSites(call) &&
        target.valuePath.values ==
            listOf(ImpactValuePathSegmentDocument.Element(offsetZero())) + source.valuePath.values
}

private fun ImpactValueModelApplicationDocument.CollectionSelection.admitsCollectionSelection(): Boolean {
    val call = (binding.invocation as? ImpactCallOccurrenceDocument.Declared)?.invocation ?: return false
    val receiver = source.role as? ImpactValueRoleDocument.Receiver ?: return false
    return receiver.kind == ImpactReceiverKindDocument.EXTENSION &&
        receiver.invocation == call &&
        hasConsistentSites(call) &&
        admitsSelectedElement()
}

private fun ImpactValueModelApplicationDocument.CollectionSelection.admitsSelectedElement(): Boolean {
    if (binding.model !is ImpactReviewedValueModelDocument.FirstOrNull) return false
    val selected = condition as? ImpactValueModelConditionDocument.SelectedElement ?: return false
    return source.valuePath.selects(ImpactValuePathSegmentDocument.Element(offsetZero()), target.valuePath) &&
        selected.receiver.hasSameValueIdentity(source.copy(valuePath = EmptyImpactValuePath)) &&
        selected.result.hasSameValueIdentity(target.copy(valuePath = EmptyImpactValuePath)) &&
        selected.index.value == 0
}

private fun ImpactValueModelApplicationDocument.hasConsistentSites(call: ImpactInvocationReferenceDocument): Boolean =
    source.enclosing == binding.enclosing.declaration &&
        target.enclosing == source.enclosing &&
        source.executionOwner == call.executionOwner &&
        target.executionOwner == call.executionOwner &&
        target.range == call.range &&
        target.role == ImpactValueRoleDocument.ExpressionResult &&
        source.callStack.hasSameCallStackIdentity(target.callStack) &&
        source.predicates.hasSamePredicateIdentity(target.predicates)

internal fun ImpactReviewedLibraryBindingDocument.admits(basis: ImpactSemanticBasisDocument): Boolean {
    val call = (invocation as? ImpactCallOccurrenceDocument.Declared)?.invocation ?: return false
    if (!enclosing.admits(basis) || !callable.admits(basis) || !execution.enclosing.admits(basis)) return false
    if (call.callable != callable.declaration || !call.executionOwner.admits(enclosing.declaration, call.range))
        return false
    if (execution.enclosing.declaration != enclosing.declaration || !execution.matches(call.executionOwner))
        return false
    if (!hasConsistentArtifact(basis)) return false
    return model.admitsReviewStructure() && callable.compilerTarget.compilerEvidence.signature == model.callable
}

private fun ImpactReviewedLibraryBindingDocument.hasConsistentArtifact(basis: ImpactSemanticBasisDocument): Boolean =
    artifact.basis == basis &&
        artifact.origin == ImpactLibraryOriginDocument.LIBRARY &&
        artifact.binaryDigest == model.artifact.binaryDigest &&
        artifact.version == model.artifact.version &&
        artifact.root.value.startsWith("jar://") &&
        artifact.root.value.endsWith("!/") &&
        callable.declaration.file.value.startsWith(artifact.root.value)

internal fun ImpactModelEndpointDocument.admits(basis: ImpactSemanticBasisDocument): Boolean =
    declaration.basis == basis &&
        declaration.declarationFailure() is Refinement.Refined &&
        compilerTarget.toContract() is WireDocumentConversion.Converted &&
        compilerTarget.file == declaration.file.value &&
        compilerTarget.range.startInclusive == declaration.range.start.value &&
        compilerTarget.range.endExclusive == declaration.range.end.value &&
        compilerTarget.compilerEvidence.identity == declaration.compilerIdentity.value &&
        fingerprint.value.length == FINGERPRINT_LENGTH &&
        fingerprint.value.all { it in '0'..'9' || it in 'a'..'f' }

internal fun ImpactModelExecutionDocument.matches(owner: ImpactExecutionOwnerDocument): Boolean =
    when (this) {
        is ImpactModelExecutionDocument.Declaration -> owner == ImpactExecutionOwnerDocument.Declaration
        is ImpactModelExecutionDocument.Anonymous ->
            owner is ImpactExecutionOwnerDocument.Anonymous &&
                body.toContract() is WireDocumentConversion.Converted &&
                body.occurrence.file == owner.body.file.value &&
                body.occurrence.range.startInclusive == owner.body.range.start.value &&
                body.occurrence.range.endExclusive == owner.body.range.end.value &&
                body.compilerEvidence.identity == owner.body.compilerIdentity.value
    }

private fun ImpactReviewedValueModelDocument.admitsReviewStructure(): Boolean =
    when (this) {
        is ImpactReviewedValueModelDocument.FirstOrNull,
        is ImpactReviewedValueModelDocument.SingleElementListOf,
        is ImpactReviewedValueModelDocument.Let ->
            callable is CompilerSignatureWireDocument.Function &&
                callable.toContract() is WireDocumentConversion.Converted &&
                reference.model.provenance.value == "source-sha256:${artifact.sourceDigest.value}"
    }

private fun offsetZero(): ProtocolOffset = (ProtocolOffset.parse(0) as Refinement.Refined).value

private const val FINGERPRINT_LENGTH = 64
