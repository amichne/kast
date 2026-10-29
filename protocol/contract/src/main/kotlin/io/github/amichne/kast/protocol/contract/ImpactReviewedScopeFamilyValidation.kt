package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal fun ImpactReviewedValueBindingDocument.admitsScopeFamily(): Boolean =
    selected.admitsScopeFamily(rule) && artifacts.admitsRule(rule) && admitsDeclaredInvocation()

private fun ImpactReviewedFunctionDocument.admitsScopeFamily(rule: ImpactReviewedValueRuleDocument): Boolean =
    when (rule) {
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_RUN_TOP_LEVEL_V1 ->
            receiver == ImpactDeclaredCallbackReceiverDocument.Absent && matchesScopeFunction("run", listOf("()->R"), 1)
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_RUN_RECEIVER_V1 ->
            receiver.hasType("T") && matchesScopeFunction("run", listOf("T.()->R"), 2)
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_WITH_V1 ->
            receiver == ImpactDeclaredCallbackReceiverDocument.Absent &&
                matchesScopeFunction("with", listOf("T", "T.()->R"), 2, 1)
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_APPLY_V1 ->
            receiver.hasType("T") && matchesScopeFunction("apply", listOf("T.()->kotlin/Unit"), 1)
        else -> false
    }

private fun ImpactReviewedFunctionDocument.matchesScopeFunction(
    name: String,
    parameters: List<String>,
    count: Int,
    position: Int = 0,
): Boolean {
    if (qualifiedIdentity.value != "kotlin.$name" || !declaration.file.value.endsWith("!/kotlin/StandardKt.class"))
        return false
    if (contextReceivers.values.isNotEmpty() || valueParameters.values.map { it.value } != parameters) return false
    if (typeParameterCount.value != count.toLong() || formalIndex.value != position) return false
    val typeCount =
        when (val parsed = CompilerTypeParameterCountDocument.parse(count)) {
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
            typeCount,
        ),
    ) is Refinement.Refined
}

internal fun ImpactReviewedValueRuleDocument.isReviewedDeclaredReturn(): Boolean =
    this in
        setOf(
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_LET_V1,
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_RUN_TOP_LEVEL_V1,
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_RUN_RECEIVER_V1,
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_WITH_V1,
        )

internal fun ImpactReviewedValueRuleDocument.isReviewedReceiverResult(): Boolean =
    this == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1 ||
        this == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_APPLY_V1

internal fun ImpactReviewedValueRuleDocument.admitsCallbackReceiver(): Boolean =
    this in
        setOf(
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_RUN_RECEIVER_V1,
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_WITH_V1,
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_APPLY_V1,
        )
