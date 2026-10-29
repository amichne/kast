package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal fun ImpactReviewedCallValueBindingDocument.admitsEntryReadOrder(): Boolean =
    when (val order = entryReadOrder) {
        ImpactReviewedEntryReadOrderDocument.Direct -> true
        is ImpactReviewedEntryReadOrderDocument.Destructured -> admitsDestructured(order)
    }

private fun ImpactReviewedCallValueBindingDocument.admitsDestructured(
    order: ImpactReviewedEntryReadOrderDocument.Destructured
): Boolean {
    if (
        rule != ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT1_V1 &&
            rule != ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT2_V1
    )
        return false
    if (
        !order.declarationRange.containsExecutionRange(invocation.range) ||
            !invocation.executionOwner.admits(enclosing, order.declarationRange)
    )
        return false
    return when (val prefix = order.prefix) {
        ImpactReviewedEntryReadPrefixDocument.Empty -> true
        is ImpactReviewedEntryReadPrefixDocument.FirstComponent ->
            rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT2_V1 &&
                order.declarationRange.containsExecutionRange(prefix.invocation.invocation.range) &&
                prefix.invocation.admitsBefore(this)
    }
}

private fun ImpactReviewedEntryComponentInvocationDocument.admitsBefore(
    current: ImpactReviewedCallValueBindingDocument
): Boolean =
    invocation.isOnBasis(current.basis) &&
        invocation.executionOwner == current.invocation.executionOwner &&
        invocation.executionOwner.admits(current.enclosing, invocation.range) &&
        invocation.range.end.value <= current.invocation.range.start.value &&
        invocation.callable == selected.declaration &&
        selected.declaration.declarationFailure() is Refinement.Refined &&
        selected.admitsOrdinary(ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT1_V1) &&
        artifacts.admitsRule(ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT1_V1) &&
        artifacts.binaryArtifactSha256 == current.artifacts.binaryArtifactSha256 &&
        artifacts.sourceArtifactSha256 == current.artifacts.sourceArtifactSha256

internal fun ImpactReviewedCallValueBindingDocument.needsEntryPrefixPremise(): Boolean =
    when (val order = entryReadOrder) {
        ImpactReviewedEntryReadOrderDocument.Direct -> false
        is ImpactReviewedEntryReadOrderDocument.Destructured ->
            order.prefix is ImpactReviewedEntryReadPrefixDocument.FirstComponent
    }
