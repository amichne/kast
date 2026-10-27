package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Structural transport admission retains the compiler claim, without upgrading it to runtime evidence. */
internal fun ImpactDeclaredCallbackValueBindingDocument.admits(host: ImpactDeclarationReferenceDocument): Boolean =
    matchesHost(host) &&
        host.range.containsExecutionRange(contract.occurrenceRange) &&
        contract.occurrenceRange.containsExecutionRange(body.range) &&
        host
            .copy(file = body.file, range = body.range, compilerIdentity = body.compilerIdentity)
            .validValueDeclaration() &&
        contract.target.declaration.basis == host.basis &&
        contract.target.admits() &&
        contract.position.value in contract.target.valueParameters.values.indices &&
        contract.classDigest.value.length == DEPENDENCY_CLASS_SHA256_LENGTH &&
        contract.classDigest.value.all { it in '0'..'9' || it in 'a'..'f' }

private fun ImpactDeclaredCallbackValueBindingDocument.matchesHost(host: ImpactDeclarationReferenceDocument): Boolean =
    enclosing == host &&
        enclosing.validValueDeclaration() &&
        host.file.value.startsWith('/') &&
        contract.basis == host.basis &&
        contract.occurrenceFile == host.file &&
        body.file == host.file &&
        contract.owner.file == host.file &&
        contract.owner.range == host.range &&
        contract.owner.compilerIdentity == host.compilerIdentity

private fun ImpactDeclaredCallbackTargetDocument.admits(): Boolean {
    if (
        !declaration.validValueDeclaration() ||
            !declaration.file.value.startsWith("jar://") ||
            !declaration.file.value.endsWith(".class")
    )
        return false
    if (typeParameterCount.value > Int.MAX_VALUE) return false
    val count =
        when (val admitted = CompilerTypeParameterCountDocument.parse(typeParameterCount.value.toInt())) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return false
        }
    val signature =
        CompilerSignatureDocument.Function(
            qualifiedIdentity,
            when (val value = receiver) {
                ImpactDeclaredCallbackReceiverDocument.Absent -> CompilerReceiverDocument.Absent
                is ImpactDeclaredCallbackReceiverDocument.Present ->
                    CompilerReceiverDocument.Present(value.compilerType)
            },
            contextReceivers,
            valueParameters,
            count,
        )
    return CompilerSymbolEvidenceDocument.restore(declaration.compilerIdentity, signature) is Refinement.Refined
}

internal fun ImpactValueConditionDocument.DeclaredCallbackResult.admitsResult(): Boolean =
    binding.admits(origin.execution.enclosing) &&
        binding.resultEffect == ImpactDeclaredCallbackResultEffectDocument.RETURNS_RESULT_OF &&
        origin.execution.executionOwner == ImpactExecutionOwnerDocument.DeclaredCallback(binding) &&
        origin.role == ImpactValueRoleDocument.Return &&
        binding.body.range.containsExecutionRange(origin.range) &&
        useRange == binding.contract.occurrenceRange

internal fun ImpactSourceOperationDocument.DeclaredCallbackResult.admitsDeclaredResult(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean =
    kind == ImpactTransferKindDocument.SOURCE_CALL_RETURN &&
        binding.admits(source.enclosing) &&
        binding.resultEffect == ImpactDeclaredCallbackResultEffectDocument.RETURNS_RESULT_OF &&
        source.executionOwner == ImpactExecutionOwnerDocument.DeclaredCallback(binding) &&
        source.role == ImpactValueRoleDocument.Return &&
        binding.admitsResultSite(target) &&
        source.valuePath == target.valuePath &&
        source.callStack.hasSameCallStackIdentity(target.callStack) &&
        source.conservesConditions(target, kind) &&
        target.conditions.values.any {
            it is ImpactValueConditionDocument.DeclaredCallbackResult &&
                it.binding == binding &&
                it.matchesReturn(source, target.range)
        }

private fun ImpactDeclaredCallbackValueBindingDocument.admitsResultSite(
    target: ImpactValueSiteReferenceDocument
): Boolean =
    target.enclosing == enclosing &&
        target.executionOwner == ImpactExecutionOwnerDocument.Declaration &&
        target.role == ImpactValueRoleDocument.ExpressionResult &&
        target.range == contract.occurrenceRange

private fun ImpactValueConditionDocument.DeclaredCallbackResult.matchesReturn(
    source: ImpactValueSiteReferenceDocument,
    resultRange: ImpactSourceRangeDocument,
): Boolean =
    origin.execution.enclosing == source.enclosing &&
        origin.execution.executionOwner == source.executionOwner &&
        origin.range == source.range &&
        origin.role == source.role &&
        origin.valuePath == source.valuePath &&
        origin.callStack.hasSameCallStackIdentity(source.callStack) &&
        useRange == resultRange

private const val DEPENDENCY_CLASS_SHA256_LENGTH = 64
