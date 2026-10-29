package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal fun ImpactReviewedValueBindingDocument.admitsMappedRule(): Boolean =
    invocationEvidence == ImpactReviewedInvocationEvidenceDocument.Unestablished &&
        selected.admitsMappedFunction() &&
        artifacts.admitsRule(rule)

private fun ImpactReviewedFunctionDocument.admitsMappedFunction(): Boolean {
    if (
        qualifiedIdentity.value != "kotlin.collections.mapValues" ||
            !receiver.hasType("kotlin/collections/Map<outK,V>") ||
            !declaration.file.value.endsWith("!/kotlin/collections/MapsKt.class")
    )
        return false
    if (
        contextReceivers.values.isNotEmpty() ||
            formalIndex.value != 0 ||
            typeParameterCount.value != MAP_VALUES_TYPE_PARAMETERS
    )
        return false
    if (valueParameters.values.map { it.value } != listOf("(kotlin/collections/Map.Entry<K,V>)->R")) return false
    val count =
        when (val parsed = CompilerTypeParameterCountDocument.parse(MAP_VALUES_TYPE_PARAMETERS.toInt())) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return false
        }
    return CompilerSymbolEvidenceDocument.restore(
        declaration.compilerIdentity,
        CompilerSignatureDocument.Function(
            qualifiedIdentity,
            receiver.compilerReceiverDocument(),
            contextReceivers,
            valueParameters,
            count,
        ),
    ) is Refinement.Refined
}

internal fun ImpactReviewedMappedEntryOriginDocument.admits(basis: ImpactSemanticBasisDocument): Boolean =
    binding.basis == basis &&
        binding.rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_VALUES_V1 &&
        binding.admits(binding.enclosing) &&
        returnExecution.enclosing == binding.enclosing &&
        returnExecution.executionOwner == ImpactExecutionOwnerDocument.ReviewedCallback(binding) &&
        binding.body.range.containsExecutionRange(returnRange) &&
        callStack.admitsReviewedStack(basis)

internal fun ImpactReviewedMappedEntryOriginDocument.matchesReturn(source: ImpactValueSiteReferenceDocument): Boolean =
    matchesReturnAnchor(source) && selection == source.reviewedEntrySelection()

private fun ImpactReviewedMappedEntryOriginDocument.matchesReturnAnchor(
    source: ImpactValueSiteReferenceDocument
): Boolean =
    source.role == ImpactValueRoleDocument.Return &&
        source.range == returnRange &&
        source.enclosing == returnExecution.enclosing &&
        source.executionOwner == returnExecution.executionOwner &&
        callStack.hasSameCallStackIdentity(source.callStack)

internal fun ImpactReviewedValueTransferDocument.admitsMapped(
    relation: ImpactReviewedValueRelationDocument.MappedValue
): Boolean {
    val proof = binding.authority as? ImpactReviewedValueBindingDocument ?: return false
    return relation.entry.matchesReturn(source) &&
        admitsCallbackResult(proof) &&
        listOf(ImpactValuePathSegmentDocument.MapEntryComponent(relation.entry, ImpactMapEntrySlotDocument.VALUE)) +
            source.valuePath.values == target.valuePath.values &&
        source.conservesMappedConditions(target, relation.entry)
}

internal fun ImpactValueConditionDocument.ReviewedMappedKeySurvives.admitsMappedKeySurvives(): Boolean =
    entry.admits(origin.execution.enclosing.basis) &&
        entry.matchesReturnAnchor(sourceSite()) &&
        entry.selection.preservesVisibleContent(sourceSite()) &&
        useRange == entry.binding.supplierRange

private fun ImpactValueSiteReferenceDocument.conservesMappedConditions(
    target: ImpactValueSiteReferenceDocument,
    entry: ImpactReviewedMappedEntryOriginDocument,
): Boolean =
    target.conditions.values.size == conditions.values.size + 1 &&
        conditions.values == target.conditions.values.dropLast(1) &&
        when (val added = target.conditions.values.last()) {
            is ImpactValueConditionDocument.ReviewedMappedKeySurvives ->
                added.entry == entry &&
                    added.useRange == target.range &&
                    added.sourceSite().hasSameValueIdentity(copy(conditions = EmptyImpactConditions))
            else -> false
        }

private const val MAP_VALUES_TYPE_PARAMETERS = 3L
