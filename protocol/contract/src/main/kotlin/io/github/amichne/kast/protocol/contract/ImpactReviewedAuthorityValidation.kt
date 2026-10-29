package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal fun ImpactReviewedValueBindingDocument.admitsReceiver(source: ImpactValueSiteReferenceDocument): Boolean =
    rule.isReviewedReceiverResult() && source.valuePath.values.isEmpty() && matchesReceiver(source)

internal fun ImpactReviewedValueBindingDocument.matchesReceiver(source: ImpactValueSiteReferenceDocument): Boolean =
    when (val receiver = source.role) {
        is ImpactValueRoleDocument.Receiver ->
            receiver.kind == ImpactReceiverKindDocument.EXTENSION &&
                source.enclosing == enclosing &&
                source.executionOwner == ImpactExecutionOwnerDocument.Declaration &&
                receiver.invocation.range == supplierRange &&
                receiver.invocation.callable == selected.declaration &&
                supplierRange.containsExecutionRange(source.range) &&
                source.range.end.value <= body.range.start.value
        else -> false
    }

internal fun ImpactReviewedValueBindingDocument.admits(host: ImpactDeclarationReferenceDocument): Boolean =
    basis == host.basis &&
        enclosing == host &&
        selected.declaration.basis == basis &&
        enclosing.declarationFailure() is Refinement.Refined &&
        selected.declaration.declarationFailure() is Refinement.Refined &&
        body.file == host.file &&
        host.range.containsExecutionRange(supplierRange) &&
        supplierRange.containsExecutionRange(body.range) &&
        enclosing
            .copy(file = body.file, range = body.range, compilerIdentity = body.compilerIdentity)
            .declarationFailure() is Refinement.Refined &&
        admitsParent() &&
        admitsSelectedRule()

private fun ImpactReviewedValueBindingDocument.admitsSelectedRule(): Boolean =
    when (rule) {
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_RUN_TOP_LEVEL_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_RUN_RECEIVER_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_WITH_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_APPLY_V1 -> admitsScopeFamily()
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_GROUP_BY_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_GROUP_BY_TRANSFORM_V1 -> admitsGroupRule()
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_FILTER_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_NOT_NULL_V1 -> admitsMapRule()
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_KEY_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_VALUE_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT1_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT2_V1 -> false
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_VALUES_V1 -> admitsMappedRule()
        ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1 -> admitsMapRule()
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_LET_V1 -> admitsLetRule()
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1 -> admitsAlsoRule()
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_TO_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_CONSTRUCTOR_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_FIRST_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_SECOND_V1 -> false
    }

private fun ImpactReviewedValueBindingDocument.admitsMapRule(): Boolean =
    invocationEvidence == ImpactReviewedInvocationEvidenceDocument.Unestablished &&
        selected.admitsReviewedCollection(rule) &&
        artifacts.admitsRule(rule)

private fun ImpactReviewedValueBindingDocument.admitsLetRule(): Boolean =
    selected.admitsScope("kotlin.let", "(T)->R", 2) &&
        artifacts is ImpactReviewedScopeArtifactsDocument &&
        artifacts.admitsRule(rule) &&
        admitsDeclaredInvocation()

private fun ImpactReviewedValueBindingDocument.admitsAlsoRule(): Boolean =
    selected.admitsScope("kotlin.also", "(T)->kotlin/Unit", 1) &&
        artifacts is ImpactReviewedScopeArtifactsDocument &&
        artifacts.admitsRule(rule) &&
        admitsDeclaredInvocation()

internal fun ImpactReviewedValueBindingDocument.admitsDeclaredInvocation(): Boolean =
    parent == ImpactReviewedCallbackParentDocument.Declaration &&
        when (val evidence = invocationEvidence) {
            ImpactReviewedInvocationEvidenceDocument.Unestablished -> false
            is ImpactReviewedInvocationEvidenceDocument.Declared ->
                evidence.binding.admits(enclosing) &&
                    evidence.binding.body == body &&
                    evidence.binding.contract.occurrenceRange == supplierRange &&
                    evidence.binding.contract.target.declaration == selected.declaration &&
                    evidence.binding.contract.position == selected.formalIndex &&
                    evidence.binding.contract.classDigest ==
                        (artifacts as? ImpactReviewedScopeArtifactsDocument)?.facadeSha256
        }

private fun ImpactReviewedFunctionDocument.admitsScope(name: String, parameter: String, typeParameters: Long): Boolean {
    if (
        qualifiedIdentity.value != name ||
            !receiver.hasType("T") ||
            !declaration.file.value.endsWith("!/kotlin/StandardKt.class")
    )
        return false
    if (contextReceivers.values.isNotEmpty() || valueParameters.values.map { it.value } != listOf(parameter))
        return false
    if (typeParameterCount.value != typeParameters || formalIndex.value != 0) return false
    val count =
        when (val parsed = CompilerTypeParameterCountDocument.parse(typeParameters.toInt())) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return false
        }
    val signature =
        CompilerSignatureDocument.Function(
            qualifiedIdentity,
            receiver.compilerReceiverDocument(),
            contextReceivers,
            valueParameters,
            count,
        )
    return CompilerSymbolEvidenceDocument.restore(declaration.compilerIdentity, signature) is Refinement.Refined
}

private fun ImpactReviewedFunctionDocument.admitsReviewedCollection(rule: ImpactReviewedValueRuleDocument): Boolean {
    val expected =
        when (rule) {
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_FILTER_V1 ->
                Triple("filter", "(T)->kotlin/Boolean", 1)
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_NOT_NULL_V1 -> Triple("mapNotNull", "(T)->R?", 2)
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_V1,
            ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1 -> Triple("map", "(T)->R", 2)
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1 -> Triple("sortedBy", "(T)->R?", 2)
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1 ->
                Triple("associate", "(T)->kotlin/Pair<K,V>", 3)
            else -> return false
        }
    if (
        qualifiedIdentity.value != "kotlin.collections.${expected.first}" ||
            !receiver.hasType("kotlin/collections/Iterable<T>") ||
            !declaration.file.value.endsWith("!/kotlin/collections/CollectionsKt.class")
    )
        return false
    if (
        contextReceivers.values.isNotEmpty() ||
            valueParameters.values.map { it.value } != listOf(expected.second) ||
            typeParameterCount.value != expected.third.toLong()
    )
        return false
    if (formalIndex.value != 0) return false
    val count =
        when (val parsed = CompilerTypeParameterCountDocument.parse(expected.third)) {
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
