package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal fun ImpactReviewedValueRuleDocument.admitsEntrySlot(slot: ImpactMapEntrySlotDocument): Boolean =
    when (this) {
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_KEY_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT1_V1 -> slot == ImpactMapEntrySlotDocument.KEY
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_VALUE_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT2_V1 -> slot == ImpactMapEntrySlotDocument.VALUE
        else -> false
    }

internal fun ImpactReviewedOrdinaryTargetDocument.Property.admitsEntryProperty(
    rule: ImpactReviewedValueRuleDocument
): Boolean {
    val matches =
        when (rule) {
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_KEY_V1 ->
                qualifiedIdentity.value == "kotlin.collections.Map.Entry.key" && returnType.value == "K"
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_VALUE_V1 ->
                qualifiedIdentity.value == "kotlin.collections.Map.Entry.value" && returnType.value == "V"
            else -> false
        }
    return matches &&
        declaration.file.value.endsWith("!/kotlin/collections/collections.kotlin_builtins") &&
        receiver == ImpactDeclaredCallbackReceiverDocument.Absent &&
        contextReceivers.values.isEmpty() &&
        CompilerSymbolEvidenceDocument.restore(
            declaration.compilerIdentity,
            CompilerSignatureDocument.Property(
                qualifiedIdentity,
                CompilerReceiverDocument.Absent,
                contextReceivers,
                returnType,
            ),
        ) is Refinement.Refined
}

internal fun ImpactDeclaredCallbackTargetDocument.admitsEntryFunction(rule: ImpactReviewedValueRuleDocument): Boolean {
    val matches =
        when (rule) {
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT1_V1 ->
                qualifiedIdentity.value == "kotlin.collections.component1"
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT2_V1 ->
                qualifiedIdentity.value == "kotlin.collections.component2"
            else -> false
        }
    val extension = receiver as? ImpactDeclaredCallbackReceiverDocument.Present ?: return false
    if (
        !matches ||
            !declaration.file.value.endsWith("!/kotlin/collections/MapsKt.class") ||
            extension.compilerType.value != "kotlin/collections/Map.Entry<K,V>"
    )
        return false
    if (contextReceivers.values.isNotEmpty() || valueParameters.values.isNotEmpty() || typeParameterCount.value != 2L)
        return false
    val count =
        when (val parsed = CompilerTypeParameterCountDocument.parse(2)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return false
        }
    return CompilerSymbolEvidenceDocument.restore(
        declaration.compilerIdentity,
        CompilerSignatureDocument.Function(
            qualifiedIdentity,
            CompilerReceiverDocument.Present(extension.compilerType),
            contextReceivers,
            valueParameters,
            count,
        ),
    ) is Refinement.Refined
}
