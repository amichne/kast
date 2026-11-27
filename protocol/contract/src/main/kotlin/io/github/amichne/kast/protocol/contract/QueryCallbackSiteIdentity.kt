package io.github.amichne.kast.protocol.contract

// Identity predicates over one callback document's declarations, callables and source sites. Each checks that the
// handles a document carries agree with their compiler facts; callback admission composes them.

internal fun QueryCallbackParameterIdentityDocument.validParameter(): Boolean {
    val signature =
        callable.compilerTarget.compilerEvidence.signature as? CompilerCallableSignatureDocument ?: return false
    return callable.validCallable() &&
        callable.declaration.contains(parameter) &&
        position.value in signature.valueParameters.values.indices
}

internal fun QueryCallbackBindingDocument.Bound.parameterIdentity() =
    QueryCallbackParameterIdentityDocument(callable, position, parameter)

internal fun QueryCallbackBindingDocument.Bound.validInvocationSite(): Boolean =
    invocation.range.start == invocationOccurrence.range.startInclusive &&
        invocation.range.end == invocationOccurrence.range.endExclusive

internal fun QueryCallbackCallableDocument.reference(basis: ImpactSemanticBasisDocument) =
    ImpactDeclarationReferenceDocument(
        basis,
        compilerTarget.file,
        ImpactSourceRangeDocument(declaration.range.startInclusive, declaration.range.endExclusive),
        compilerTarget.compilerEvidence.identity,
    )

internal fun QueryCallbackTargetDocument.validDeclaration(): Boolean =
    when (this) {
        is QueryCallbackTargetDocument.Source -> callable.validDeclaration()
        is QueryCallbackTargetDocument.Binary -> compilerTarget.compilerEvidence.signature.supports(compilerTarget.kind)
    }

internal fun QueryCallbackCallableDocument.validDeclaration(): Boolean =
    declaration.file == compilerTarget.file &&
        declaration.range == compilerTarget.range &&
        compilerTarget.compilerEvidence.signature.supports(compilerTarget.kind)

internal fun QueryCallbackCallableDocument.validCallable(): Boolean =
    validDeclaration() && compilerTarget.compilerEvidence.signature is CompilerCallableSignatureDocument

internal fun QueryCallbackBodyDocument.Anonymous.validBody(): Boolean {
    val signature = compilerEvidence.signature as? CompilerSignatureDocument.Function ?: return false
    return signature.qualifiedIdentity.value ==
        "anonymous@${occurrence.file.value}#${occurrence.range.startInclusive.value}:${occurrence.range.endExclusive.value}"
}

internal fun RelationOccurrenceDocument.contains(other: RelationOccurrenceDocument): Boolean =
    file == other.file &&
        range.startInclusive.value <= other.range.startInclusive.value &&
        range.endExclusive.value >= other.range.endExclusive.value

internal fun RelationOccurrenceDocument.sameSite(other: RelationOccurrenceDocument): Boolean =
    file == other.file && range == other.range
