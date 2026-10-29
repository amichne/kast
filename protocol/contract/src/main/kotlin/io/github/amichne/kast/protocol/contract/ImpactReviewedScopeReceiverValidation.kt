package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal fun ImpactReviewedCallbackReceiverDocument.admits(host: ImpactDeclarationReferenceDocument): Boolean =
    binding.admits(host) &&
        binding.rule.admitsCallbackReceiver() &&
        binding.invocationEvidence == ImpactReviewedInvocationEvidenceDocument.Declared(declared) &&
        kind == ImpactReceiverKindDocument.EXTENSION &&
        admitsBodySignature()

private fun ImpactReviewedCallbackReceiverDocument.admitsBodySignature(): Boolean {
    val body = binding.body
    val name =
        when (
            val value =
                ProtocolText.parse("anonymous@${body.file.value}#${body.range.start.value}:${body.range.end.value}")
        ) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return false
        }
    val count =
        when (val value = CompilerTypeParameterCountDocument.parse(0)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return false
        }
    val empty =
        when (val value = BoundedProtocolList.create<ProtocolText>(emptyList())) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return false
        }
    return CompilerSymbolEvidenceDocument.restore(
        body.compilerIdentity,
        CompilerSignatureDocument.Function(
            name,
            CompilerReceiverDocument.Present(compilerType),
            empty,
            empty,
            count,
        ),
    ) is Refinement.Refined
}

internal fun ImpactReviewedValueTransferDocument.admitsScopeReceiver(
    relation: ImpactReviewedValueRelationDocument.CallbackReceiverInput
): Boolean {
    val receiver = relation.receiver
    return source.matchesScopeActual(receiver.binding) &&
        source.valuePath == target.valuePath &&
        source.conditions.hasSameConditions(target.conditions) &&
        target.range == receiver.binding.body.range &&
        target.executionOwner == ImpactExecutionOwnerDocument.DeclaredCallback(receiver.declared) &&
        target.role == ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Receiver(receiver.kind))
}

private fun ImpactValueSiteReferenceDocument.matchesScopeActual(binding: ImpactReviewedValueBindingDocument): Boolean {
    if (
        executionOwner != binding.parentExecutionOwner ||
            !binding.supplierRange.containsExecutionRange(range) ||
            range.end.value > binding.body.range.start.value
    )
        return false
    val call =
        when (val actual = role) {
            is ImpactValueRoleDocument.Receiver -> {
                if (
                    binding.rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_WITH_V1 ||
                        actual.kind != ImpactReceiverKindDocument.EXTENSION
                )
                    return false
                actual.invocation
            }
            is ImpactValueRoleDocument.Argument -> {
                if (binding.rule != ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_WITH_V1 || actual.index.value != 0)
                    return false
                actual.invocation
            }
            else -> return false
        }
    return call.range == binding.supplierRange &&
        call.callable == binding.selected.declaration &&
        call.executionOwner == executionOwner
}
