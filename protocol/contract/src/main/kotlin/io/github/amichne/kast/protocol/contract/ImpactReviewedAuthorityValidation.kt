package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Canonical owner and applied relation share authority fields without sharing their semantic roles. */
private data class ReviewedAuthority(
    val rule: ImpactReviewedValueRuleDocument,
    val basis: ImpactSemanticBasisDocument,
    val enclosing: ImpactDeclarationReferenceDocument,
    val body: ImpactCapturedBodyDocument,
    val supplierRange: ImpactSourceRangeDocument,
    val selected: ImpactReviewedFunctionDocument,
    val artifacts: ImpactReviewedArtifactsDocument,
    val actualExecution: ImpactActualExecutionDocument,
    val invocationCount: ImpactInvocationCountKnowledgeDocument,
    val invocationEvidence: ImpactReviewedInvocationEvidenceDocument,
)

private fun ImpactReviewedValueBindingDocument.authority() =
    ReviewedAuthority(
        rule,
        basis,
        enclosing,
        body,
        supplierRange,
        selected,
        artifacts,
        actualExecution,
        invocationCount,
        invocationEvidence,
    )

private fun ImpactReviewedValueRelationshipDocument.authority() =
    ReviewedAuthority(
        rule,
        basis,
        enclosing,
        body,
        supplierRange,
        selected,
        artifacts,
        actualExecution,
        invocationCount,
        invocationEvidence,
    )

internal fun ImpactReviewedValueBindingDocument.admits(host: ImpactDeclarationReferenceDocument): Boolean =
    semantics == rule.canonicalSemantics() && authority().admits(host)

internal fun ImpactReviewedValueRelationshipDocument.admits(host: ImpactDeclarationReferenceDocument): Boolean =
    authority().admits(host) && admitsRelationship()

internal fun ImpactReviewedValueRelationshipDocument.matchesOwner(
    binding: ImpactReviewedValueBindingDocument
): Boolean = binding.admits(binding.enclosing) && authority() == binding.authority()

private fun ImpactReviewedValueRelationshipDocument.admitsRelationship(): Boolean =
    when (semantics) {
        ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT -> rule.isMap()
        ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT ->
            rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_LET_V1
        ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_TO_CALL_RESULT ->
            rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1
        ImpactReviewedValueSemanticsDocument.CONDITIONAL_INPUT_ELEMENT_TO_CALLBACK_PARAMETER,
        ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER -> rule.isMap()
    }

private fun ImpactReviewedValueRuleDocument.isMap(): Boolean =
    when (this) {
        ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_V1 -> true
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_LET_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1 -> false
    }

private fun ImpactReviewedValueRuleDocument.canonicalSemantics(): ImpactReviewedValueSemanticsDocument =
    when (this) {
        ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_V1 ->
            ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_LET_V1 ->
            ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1 ->
            ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_TO_CALL_RESULT
    }

internal fun ImpactReviewedValueBindingDocument.admitsReceiver(source: ImpactValueSiteReferenceDocument): Boolean =
    rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1 &&
        source.valuePath.values.isEmpty() &&
        authority().matchesReceiver(source)

internal fun ImpactReviewedValueRelationshipDocument.matchesReceiver(
    source: ImpactValueSiteReferenceDocument
): Boolean = authority().matchesReceiver(source)

private fun ReviewedAuthority.matchesReceiver(source: ImpactValueSiteReferenceDocument): Boolean =
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

private fun ReviewedAuthority.admits(host: ImpactDeclarationReferenceDocument): Boolean =
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
        admitsSelectedRule()

private fun ReviewedAuthority.admitsSelectedRule(): Boolean =
    when (rule) {
        ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_V1 -> admitsMapRule()
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_LET_V1 -> admitsLetRule()
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1 -> admitsAlsoRule()
    }

private fun ReviewedAuthority.admitsMapRule(): Boolean =
    invocationEvidence == ImpactReviewedInvocationEvidenceDocument.Unestablished &&
        selected.admitsReviewedMap() &&
        artifacts is ImpactReviewedMapArtifactsDocument &&
        when (rule) {
            ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1 -> artifacts.admitsReviewedMap()
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_V1 -> artifacts.admitsReviewedMap230()
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_LET_V1,
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1 -> false
        }

private fun ReviewedAuthority.admitsLetRule(): Boolean =
    selected.admitsScope("kotlin.let", "(T)->R", 2) &&
        artifacts is ImpactReviewedScopeArtifactsDocument &&
        artifacts.admitsScope(REVIEWED_LET_FIRST_LINE, REVIEWED_LET_LAST_LINE) &&
        admitsDeclaredInvocation()

private fun ReviewedAuthority.admitsAlsoRule(): Boolean =
    selected.admitsScope("kotlin.also", "(T)->kotlin/Unit", 1) &&
        artifacts is ImpactReviewedScopeArtifactsDocument &&
        artifacts.admitsScope(REVIEWED_ALSO_FIRST_LINE, REVIEWED_ALSO_LAST_LINE) &&
        admitsDeclaredInvocation()

private fun ReviewedAuthority.admitsDeclaredInvocation(): Boolean =
    when (val evidence = invocationEvidence) {
        ImpactReviewedInvocationEvidenceDocument.Unestablished -> false
        is ImpactReviewedInvocationEvidenceDocument.Declared ->
            evidence.binding.admits(enclosing) &&
                evidence.binding.body == body &&
                evidence.binding.contract.occurrenceRange == supplierRange &&
                evidence.binding.contract.target.declaration == selected.declaration &&
                evidence.binding.contract.position == selected.formalIndex &&
                evidence.binding.contract.classDigest == artifacts.facadeSha256
    }

private fun ImpactReviewedFunctionDocument.admitsReviewedMap(): Boolean {
    if (
        qualifiedIdentity.value != "kotlin.collections.map" ||
            receiver.value != "kotlin/collections/Iterable<T>" ||
            !declaration.file.value.endsWith("!/kotlin/collections/CollectionsKt.class")
    )
        return false
    if (contextReceivers.values.isNotEmpty() || valueParameters.values.map { it.value } != listOf("(T)->R"))
        return false
    if (typeParameterCount.value != 2L || formalIndex.value != 0) return false
    val count =
        when (val parsed = CompilerTypeParameterCountDocument.parse(typeParameterCount.value.toInt())) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return false
        }
    val signature =
        CompilerSignatureDocument.Function(
            qualifiedIdentity,
            CompilerReceiverDocument.Present(receiver),
            contextReceivers,
            valueParameters,
            count,
        )
    return CompilerSymbolEvidenceDocument.restore(declaration.compilerIdentity, signature) is Refinement.Refined
}

private fun ImpactReviewedMapArtifactsDocument.admitsReviewedMap(): Boolean =
    binaryArtifactSha256.value == "bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3" &&
        sourceArtifactSha256.value == "1e29c0668b0aa555e7b154e5da2e8094e1b755cfee25737839195bbbdad8a4a2" &&
        sourceEntrySha256.value == "aad6a5398189369be27f65952a5f2c935e3bf00d166e2046a0fb4be0d007a85c" &&
        facadeSha256.value == "7f74e68459e9c8ae6c69510014b6fe0f565b9389ddbe5acb5e181dd773558878" &&
        implementationSha256.value == "e5afca255b864c70152adeeecd88367f5477966ab26bd16f8db2b5c56c995296" &&
        mapLines.first.value == REVIEWED_MAP_FIRST_LINE &&
        mapLines.last.value == REVIEWED_MAP_LAST_LINE &&
        mapToLines.first.value == REVIEWED_MAP_TO_FIRST_LINE &&
        mapToLines.last.value == REVIEWED_MAP_TO_LAST_LINE

private fun ImpactReviewedFunctionDocument.admitsScope(name: String, parameter: String, typeParameters: Long): Boolean {
    if (
        qualifiedIdentity.value != name ||
            receiver.value != "T" ||
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
            CompilerReceiverDocument.Present(receiver),
            contextReceivers,
            valueParameters,
            count,
        )
    return CompilerSymbolEvidenceDocument.restore(declaration.compilerIdentity, signature) is Refinement.Refined
}

private fun ImpactReviewedScopeArtifactsDocument.admitsScope(first: Int, last: Int): Boolean =
    binaryArtifactSha256.value == "887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1" &&
        sourceArtifactSha256.value == "326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531" &&
        sourceEntrySha256.value == "86b7320d868d66492d277ebb5d5d8ad78da074d592dacf32f186a83d8d3196a0" &&
        facadeSha256.value == "f7d71cc1a37a29843d8e3a0b660afa358bdf0f8ac3ffb566a0e79c004f7ffd05" &&
        implementationSha256.value == "bfdad3872af89a51041163c03afda592baea4e394a8b1665de5b7547f4f43ce0" &&
        functionLines.first.value == first &&
        functionLines.last.value == last

private const val REVIEWED_MAP_FIRST_LINE = 1744
private const val REVIEWED_MAP_LAST_LINE = 1746
private const val REVIEWED_MAP_TO_FIRST_LINE = 1819
private const val REVIEWED_MAP_TO_LAST_LINE = 1823

private const val REVIEWED_LET_FIRST_LINE = 114
private const val REVIEWED_LET_LAST_LINE = 119
private const val REVIEWED_ALSO_FIRST_LINE = 99
private const val REVIEWED_ALSO_LAST_LINE = 105

private fun ImpactReviewedMapArtifactsDocument.admitsReviewedMap230(): Boolean =
    binaryArtifactSha256.value == "887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1" &&
        sourceArtifactSha256.value == "326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531" &&
        sourceEntrySha256.value == "03b2c7e97271674123941c6d1df5f95a8511d7b1d543fd41d4523af6c15e2970" &&
        facadeSha256.value == "516c679f0309fb8a7058dfc33da4f11a4d0b5eab6de8f2225ff15a9ff3e88770" &&
        implementationSha256.value == "fb24111a87f4e45b9a082ea1666dd33eb04d7ce979b24fde34fcb6d229371e39" &&
        mapLines.first.value == REVIEWED_MAP_230_FIRST_LINE &&
        mapLines.last.value == REVIEWED_MAP_230_LAST_LINE &&
        mapToLines.first.value == REVIEWED_MAP_TO_230_FIRST_LINE &&
        mapToLines.last.value == REVIEWED_MAP_TO_230_LAST_LINE

private const val REVIEWED_MAP_230_FIRST_LINE = 1585
private const val REVIEWED_MAP_230_LAST_LINE = 1587
private const val REVIEWED_MAP_TO_230_FIRST_LINE = 1660
private const val REVIEWED_MAP_TO_230_LAST_LINE = 1664
