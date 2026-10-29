package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Wire admission preserves the reviewed identities without asserting an actual execution. */
fun ImpactReviewedValueTransferDocument.admitsEvidence(): Boolean =
    binding.admits(binding.enclosing) &&
        source.isOnBasis(binding.basis) &&
        target.isOnBasis(binding.basis) &&
        source.enclosing == binding.enclosing &&
        target.enclosing == binding.enclosing &&
        admitsSource() &&
        target.executionOwner == ImpactExecutionOwnerDocument.Declaration &&
        target.role == ImpactValueRoleDocument.ExpressionResult &&
        target.range == binding.supplierRange &&
        preservesState()

private fun ImpactReviewedValueTransferDocument.preservesState(): Boolean =
    preservesConditions() &&
        source.callStack.hasSameCallStackIdentity(target.callStack) &&
        when (binding.rule) {
            ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1 ->
                listOf(ImpactValuePathSegmentDocument.Element) + source.valuePath.values == target.valuePath.values
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_LET_V1 -> source.valuePath == target.valuePath
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1 ->
                source.valuePath.values.isEmpty() && target.valuePath.values.isEmpty()
        }

private fun ImpactReviewedValueTransferDocument.preservesConditions(): Boolean =
    when (binding.rule) {
        ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1,
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_LET_V1 -> source.conditions.hasSameConditions(target.conditions)
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1 ->
            source.conservesReviewedReceiverConditions(target, binding)
    }

private fun ImpactReviewedValueTransferDocument.admitsSource(): Boolean =
    when (binding.rule) {
        ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1 ->
            source.role == ImpactValueRoleDocument.Return &&
                source.executionOwner == ImpactExecutionOwnerDocument.ReviewedCallback(binding)
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_LET_V1 ->
            when (val invocation = binding.invocationEvidence) {
                ImpactReviewedInvocationEvidenceDocument.Unestablished -> false
                is ImpactReviewedInvocationEvidenceDocument.Declared ->
                    source.role == ImpactValueRoleDocument.Return &&
                        source.executionOwner == ImpactExecutionOwnerDocument.DeclaredCallback(invocation.binding)
            }
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1 -> binding.admitsReceiver(source)
    }

internal fun ImpactReviewedValueBindingDocument.admitsReceiver(source: ImpactValueSiteReferenceDocument): Boolean =
    when (val receiver = source.role) {
        is ImpactValueRoleDocument.Receiver ->
            receiver.kind == ImpactReceiverKindDocument.EXTENSION &&
                source.executionOwner == ImpactExecutionOwnerDocument.Declaration &&
                receiver.invocation.range == supplierRange &&
                receiver.invocation.callable == selected.declaration &&
                supplierRange.containsExecutionRange(source.range) &&
                source.range.end.value <= body.range.start.value &&
                source.valuePath.values.isEmpty()
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
        admitsSelectedRule()

private fun ImpactReviewedValueBindingDocument.admitsSelectedRule(): Boolean =
    when (rule) {
        ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1 -> admitsMapRule()
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_LET_V1 -> admitsLetRule()
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1 -> admitsAlsoRule()
    }

private fun ImpactReviewedValueBindingDocument.admitsMapRule(): Boolean =
    semantics == ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT &&
        invocationEvidence == ImpactReviewedInvocationEvidenceDocument.Unestablished &&
        selected.admitsReviewedMap() &&
        artifacts is ImpactReviewedMapArtifactsDocument &&
        artifacts.admitsReviewedMap()

private fun ImpactReviewedValueBindingDocument.admitsLetRule(): Boolean =
    semantics == ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT &&
        selected.admitsScope("kotlin.let", "(T)->R", 2) &&
        artifacts is ImpactReviewedScopeArtifactsDocument &&
        artifacts.admitsScope(REVIEWED_LET_FIRST_LINE, REVIEWED_LET_LAST_LINE) &&
        admitsDeclaredInvocation()

private fun ImpactReviewedValueBindingDocument.admitsAlsoRule(): Boolean =
    semantics == ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_TO_CALL_RESULT &&
        selected.admitsScope("kotlin.also", "(T)->kotlin/Unit", 1) &&
        artifacts is ImpactReviewedScopeArtifactsDocument &&
        artifacts.admitsScope(REVIEWED_ALSO_FIRST_LINE, REVIEWED_ALSO_LAST_LINE) &&
        admitsDeclaredInvocation()

private fun ImpactReviewedValueBindingDocument.admitsDeclaredInvocation(): Boolean =
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
