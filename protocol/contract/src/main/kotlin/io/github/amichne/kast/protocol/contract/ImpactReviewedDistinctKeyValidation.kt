package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal fun ImpactReviewedMapEntryCorrelationDocument.admitsKeyEvidence(): Boolean =
    when (val proof = keyEvidence) {
        ImpactReviewedEntryKeyEvidenceDocument.Conditional -> true
        is ImpactReviewedEntryKeyEvidenceDocument.Distinct ->
            proof.proof.admitsDistinctKeys() &&
                proof.proof.key.binding == binding &&
                proof.proof.key.tupleOrigin == tupleOrigin &&
                proof.proof.key.returnRange == returnRange &&
                proof.proof.key.formal.executionOwner == returnExecution.executionOwner
    }

private fun ImpactReviewedDistinctStringKeysDocument.admitsDistinctKeys(): Boolean {
    val binding = key.binding
    if (!input.admitsStringList() || !key.admitsDirectKey()) return false
    if (input.formal.enclosing != binding.enclosing || input.formal.invocation.range != receiverRange) return false
    return associationInvocation.range == binding.supplierRange &&
        associationInvocation.callable == binding.selected.declaration &&
        associationInvocation.executionOwner == ImpactExecutionOwnerDocument.Declaration &&
        input.formal.invocation.executionOwner == ImpactExecutionOwnerDocument.Declaration &&
        binding.supplierRange.containsExecutionRange(receiverRange) &&
        receiverRange.end.value <= binding.body.range.start.value &&
        input.arguments.values.map { it.value.value }.toSet().size == input.arguments.values.size
}

private fun ImpactReviewedDirectFormalKeyDocument.admitsDirectKey(): Boolean {
    if (
        !binding.admits(binding.enclosing) ||
            binding.rule != ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1
    )
        return false
    if (!formal.admits() || formal.index.value != 0 || formal.callable != binding.enclosing) return false
    if (formal.executionOwner != ImpactExecutionOwnerDocument.ReviewedCallback(binding)) return false
    return tupleOrigin.admits(binding.basis) &&
        tupleOrigin.binding.enclosing == binding.enclosing &&
        tupleOrigin.binding.invocation.executionOwner == formal.executionOwner &&
        tupleOrigin.binding.invocation.range == returnRange &&
        returnRange.containsExecutionRange(keyRange)
}

private fun ImpactReviewedStringListInputDocument.admitsStringList(): Boolean {
    if (!formal.admitsVararg() || !artifact.admitsStringListArtifact()) return false
    val count = actualArgumentCount.value
    if (
        count < MIN_STRING_ARGUMENTS ||
            count != arguments.values.size.toLong() ||
            count != actualArgumentRanges.values.size.toLong()
    )
        return false
    return arguments.values.withIndex().all { (index, argument) ->
        argument.formal == formal &&
            argument.ordinal.value == index &&
            argument.range == actualArgumentRanges.values[index] &&
            formal.invocation.range.containsExecutionRange(argument.range)
    } && actualArgumentRanges.values.zipWithNext().all { (first, second) -> first.end.value <= second.start.value }
}

private fun ImpactReviewedVarargFormalDocument.admitsVararg(): Boolean {
    if (position.value != 0 || target.declaration != invocation.callable) return false
    if (!invocation.isOnBasis(enclosing.basis) || !invocation.executionOwner.admits(enclosing, invocation.range))
        return false
    if (
        enclosing.declarationFailure() !is Refinement.Refined ||
            target.declaration.declarationFailure() !is Refinement.Refined
    )
        return false
    if (!target.matchesVarargSignature()) return false
    return CompilerSymbolEvidenceDocument.restore(
        target.declaration.compilerIdentity,
        CompilerSignatureDocument.Function(
            target.qualifiedIdentity,
            CompilerReceiverDocument.Absent,
            target.contextReceivers,
            target.valueParameters,
            (CompilerTypeParameterCountDocument.parse(1) as Refinement.Refined).value,
        ),
    ) is Refinement.Refined
}

private fun ImpactDeclaredCallbackTargetDocument.matchesVarargSignature(): Boolean =
    declaration.file.value.endsWith("!/kotlin/collections/CollectionsKt.class") &&
        qualifiedIdentity.value == "kotlin.collections.listOf" &&
        receiver == ImpactDeclaredCallbackReceiverDocument.Absent &&
        contextReceivers.values.isEmpty() &&
        valueParameters.values.map { it.value } == listOf("T") &&
        typeParameterCount.value == 1L

private const val MIN_STRING_ARGUMENTS = 2L
