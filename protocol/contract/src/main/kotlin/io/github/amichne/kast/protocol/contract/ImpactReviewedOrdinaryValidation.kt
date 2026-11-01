package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal fun ImpactReviewedCallValueBindingDocument.admits(host: ImpactDeclarationReferenceDocument): Boolean =
    enclosing == host &&
        basis == host.basis &&
        host.declarationFailure() is Refinement.Refined &&
        invocation.isOnBasis(basis) &&
        invocation.executionOwner.admits(host, invocation.range) &&
        invocation.callable == selected.declaration &&
        selected.declaration.declarationFailure() is Refinement.Refined &&
        selected.admitsOrdinary(rule) &&
        artifacts.admitsRule(rule) &&
        admitsEntryReadOrder()

internal fun ImpactReviewedOrdinaryTargetDocument.admitsOrdinary(rule: ImpactReviewedValueRuleDocument): Boolean =
    when (this) {
        is ImpactReviewedOrdinaryTargetDocument.Function ->
            (signature.admitsTupleFunction(rule) || signature.admitsEntryFunction(rule))
        is ImpactReviewedOrdinaryTargetDocument.Property -> admitsTupleProperty(rule) || admitsEntryProperty(rule)
    }

private fun ImpactDeclaredCallbackTargetDocument.admitsTupleFunction(rule: ImpactReviewedValueRuleDocument): Boolean {
    val matches =
        when (rule) {
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_TO_V1 -> matchesToFunction()
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_CONSTRUCTOR_V1 -> matchesPairConstructor()
            else -> false
        }
    if (!matches || contextReceivers.values.isNotEmpty()) return false
    val count =
        when (val value = CompilerTypeParameterCountDocument.parse(typeParameterCount.value.toInt())) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return false
        }
    return CompilerSymbolEvidenceDocument.restore(
        declaration.compilerIdentity,
        CompilerSignatureDocument.Function(
            qualifiedIdentity,
            receiver.compilerReceiver(),
            contextReceivers,
            valueParameters,
            count,
        ),
    ) is Refinement.Refined
}

private fun ImpactDeclaredCallbackTargetDocument.matchesToFunction(): Boolean =
    declaration.file.value.endsWith("!/kotlin/TuplesKt.class") &&
        qualifiedIdentity.value == "kotlin.to" &&
        receiver is ImpactDeclaredCallbackReceiverDocument.Present &&
        receiver.compilerType.value == "A" &&
        valueParameters.values.map { it.value } == listOf("B") &&
        typeParameterCount.value == 2L

private fun ImpactDeclaredCallbackTargetDocument.matchesPairConstructor(): Boolean =
    declaration.file.value.endsWith("!/kotlin/Pair.class") &&
        qualifiedIdentity.value == "kotlin.Pair.<init>" &&
        receiver == ImpactDeclaredCallbackReceiverDocument.Absent &&
        valueParameters.values.map { it.value } == listOf("A", "B") &&
        typeParameterCount.value == 0L

private fun ImpactReviewedOrdinaryTargetDocument.Property.admitsTupleProperty(
    rule: ImpactReviewedValueRuleDocument
): Boolean {
    val selected =
        when (rule) {
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_FIRST_V1 ->
                qualifiedIdentity.value == "kotlin.Pair.first" && returnType.value == "A"
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_SECOND_V1 ->
                qualifiedIdentity.value == "kotlin.Pair.second" && returnType.value == "B"
            else -> false
        }
    return selected &&
        declaration.file.value.endsWith("!/kotlin/Pair.class") &&
        receiver == ImpactDeclaredCallbackReceiverDocument.Absent &&
        contextReceivers.values.isEmpty() &&
        CompilerSymbolEvidenceDocument.restore(
            declaration.compilerIdentity,
            CompilerSignatureDocument.Property(
                qualifiedIdentity,
                receiver.compilerReceiver(),
                contextReceivers,
                returnType,
            ),
        ) is Refinement.Refined
}

private fun ImpactDeclaredCallbackReceiverDocument.compilerReceiver(): CompilerReceiverDocument =
    when (this) {
        ImpactDeclaredCallbackReceiverDocument.Absent -> CompilerReceiverDocument.Absent
        is ImpactDeclaredCallbackReceiverDocument.Present -> CompilerReceiverDocument.Present(compilerType)
    }

internal fun ImpactReviewedTupleOriginDocument.admits(basis: ImpactSemanticBasisDocument): Boolean =
    binding.basis == basis &&
        binding.admits(binding.enclosing) &&
        binding.rule in
            setOf(
                ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_TO_V1,
                ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_CONSTRUCTOR_V1,
            ) &&
        callStack.admitsReviewedStack(basis)

internal fun ImpactReviewedMapEntryCorrelationDocument.admits(basis: ImpactSemanticBasisDocument): Boolean =
    binding.basis == basis &&
        binding.rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1 &&
        binding.admits(binding.enclosing) &&
        tupleOrigin.admits(basis) &&
        returnExecution.enclosing == binding.enclosing &&
        returnExecution.executionOwner == ImpactExecutionOwnerDocument.ReviewedCallback(binding) &&
        binding.body.range.containsExecutionRange(returnRange) &&
        callStack.admitsReviewedStack(basis) &&
        admitsKeyEvidence()

internal fun BoundedProtocolList<ImpactCallFrameDocument>.admitsReviewedStack(
    basis: ImpactSemanticBasisDocument
): Boolean =
    values.size <= MAX_VALUE_CALL_FRAMES &&
        values.all { it.admits(basis) } &&
        values.withIndex().all { (index, frame) ->
            values.take(index).none { it.hasSameFrameIdentity(frame) }
        }
