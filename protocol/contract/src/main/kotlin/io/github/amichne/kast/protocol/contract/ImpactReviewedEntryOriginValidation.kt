package io.github.amichne.kast.protocol.contract

internal fun ImpactReviewedMapEntryFamilyDocument.admits(basis: ImpactSemanticBasisDocument): Boolean =
    binding.basis == basis &&
        binding.rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_VALUES_V1 &&
        binding.admits(binding.enclosing) &&
        callStack.admitsReviewedStack(basis)

internal fun ImpactReviewedInputEntryOriginDocument.admits(basis: ImpactSemanticBasisDocument): Boolean =
    family.admits(basis) && family.admitsReceiverAnchor(receiverRange, receiverExecution)

internal fun ImpactReviewedPreservedKeyOriginDocument.admits(basis: ImpactSemanticBasisDocument): Boolean =
    family.admits(basis) && family.admitsReceiverAnchor(receiverRange, receiverExecution)

private fun ImpactReviewedMapEntryFamilyDocument.admitsReceiverAnchor(
    range: ImpactSourceRangeDocument,
    execution: ImpactExecutionReferenceDocument,
): Boolean =
    execution.enclosing == binding.enclosing &&
        execution.executionOwner == binding.parentExecutionOwner &&
        execution.executionOwner.admits(execution.enclosing, range) &&
        binding.supplierRange.containsExecutionRange(range) &&
        range.end.value <= binding.body.range.start.value

internal fun ImpactReviewedMapEntryOriginDocument.admitsSlot(
    basis: ImpactSemanticBasisDocument,
    slot: ImpactMapEntrySlotDocument,
): Boolean =
    when (this) {
        is ImpactReviewedGroupBucketDocument -> slot == this.slot && admits(basis)
        is ImpactReviewedMapEntryCorrelationDocument -> admits(basis)
        is ImpactReviewedMappedEntryOriginDocument -> slot == ImpactMapEntrySlotDocument.VALUE && admits(basis)
        is ImpactReviewedInputEntryOriginDocument -> admits(basis)
        is ImpactReviewedPreservedKeyOriginDocument -> slot == ImpactMapEntrySlotDocument.KEY && admits(basis)
    }

internal fun ImpactReviewedMapEntryFamilyDocument.matchesReceiver(source: ImpactValueSiteReferenceDocument): Boolean {
    val receiver = source.role as? ImpactValueRoleDocument.Receiver ?: return false
    return source.enclosing == binding.enclosing &&
        source.executionOwner == binding.parentExecutionOwner &&
        receiver.kind == ImpactReceiverKindDocument.EXTENSION &&
        receiver.invocation.range == binding.supplierRange &&
        receiver.invocation.callable == binding.selected.declaration &&
        receiver.invocation.executionOwner == source.executionOwner &&
        binding.supplierRange.containsExecutionRange(source.range) &&
        source.range.end.value <= binding.body.range.start.value &&
        callStack.hasSameCallStackIdentity(source.callStack)
}

internal fun ImpactReviewedInputEntryOriginDocument.matchesReceiver(source: ImpactValueSiteReferenceDocument): Boolean =
    family.matchesReceiver(source) &&
        source.range == receiverRange &&
        source.enclosing == receiverExecution.enclosing &&
        source.executionOwner == receiverExecution.executionOwner

internal fun ImpactReviewedPreservedKeyOriginDocument.matchesReceiver(
    source: ImpactValueSiteReferenceDocument
): Boolean =
    family.matchesReceiver(source) &&
        source.range == receiverRange &&
        source.enclosing == receiverExecution.enclosing &&
        source.executionOwner == receiverExecution.executionOwner
