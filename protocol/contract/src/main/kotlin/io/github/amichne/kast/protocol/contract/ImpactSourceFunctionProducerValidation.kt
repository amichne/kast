package io.github.amichne.kast.protocol.contract

/** Structural output correspondence never grants native property origin or immutability. */
internal fun ImpactWitnessDocument.ProducerSourceFunction.admitsSourceFunctionProducer(
    basis: ImpactSemanticBasisDocument
): Boolean =
    site.isOnBasis(basis) &&
        invocation.admitsFunction() &&
        hasDeclarationProofs(basis) &&
        hasMatchingResult() &&
        hasReceiverReference()

private fun ImpactWitnessDocument.ProducerSourceFunction.hasDeclarationProofs(
    basis: ImpactSemanticBasisDocument
): Boolean =
    enclosing.admits(basis) &&
        execution.enclosing.admits(basis) &&
        property.admits(basis) &&
        property.compilerTarget.kind == SymbolKindWireDocument.PROPERTY &&
        property.compilerTarget.compilerEvidence.signature is CompilerSignatureWireDocument.Property

private fun ImpactWitnessDocument.ProducerSourceFunction.hasMatchingResult(): Boolean =
    site.enclosing == enclosing.declaration &&
        execution.enclosing == enclosing &&
        execution.matches(invocation.executionOwner) &&
        site.role == ImpactValueRoleDocument.ExpressionResult &&
        site.range == invocation.range &&
        site.executionOwner == invocation.executionOwner

private fun ImpactWitnessDocument.ProducerSourceFunction.hasReceiverReference(): Boolean =
    reference.file == site.enclosing.file &&
        reference.range.start.value >= invocation.range.start.value &&
        reference.range.start.value < reference.range.end.value &&
        reference.range.end.value <= invocation.range.end.value
