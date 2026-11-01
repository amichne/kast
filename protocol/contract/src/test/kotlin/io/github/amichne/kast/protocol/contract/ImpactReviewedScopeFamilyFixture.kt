package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal enum class ScopeFamily {
    TOP_RUN,
    RECEIVER_RUN,
    WITH,
    APPLY,
}

internal class ImpactReviewedScopeFamilyFixture(val family: ScopeFamily) {
    private val existing = ImpactReviewedScopeFixture()
    val base = existing.base
    private val absent = family == ScopeFamily.TOP_RUN || family == ScopeFamily.WITH
    private val name =
        when (family) {
            ScopeFamily.TOP_RUN,
            ScopeFamily.RECEIVER_RUN -> "run"
            ScopeFamily.WITH -> "with"
            ScopeFamily.APPLY -> "apply"
        }
    private val parameters =
        when (family) {
            ScopeFamily.TOP_RUN -> listOf("()->R")
            ScopeFamily.RECEIVER_RUN -> listOf("T.()->R")
            ScopeFamily.WITH -> listOf("T", "T.()->R")
            ScopeFamily.APPLY -> listOf("T.()->kotlin/Unit")
        }
    private val count = if (family == ScopeFamily.TOP_RUN || family == ScopeFamily.APPLY) 1 else 2
    private val position = base.offset(if (family == ScopeFamily.WITH) 1 else 0)
    private val receiver: ImpactDeclaredCallbackReceiverDocument =
        if (absent) ImpactDeclaredCallbackReceiverDocument.Absent
        else ImpactDeclaredCallbackReceiverDocument.Present(base.text("T"))
    private val signature =
        CompilerSignatureDocument.Function(
            base.text("kotlin.$name"),
            receiver.compilerReceiverDocument(),
            base.bounded(emptyList()),
            base.bounded(parameters.map(base::text)),
            CompilerTypeParameterCountDocument.parse(count).familyValue(),
        )
    private val target =
        existing.declaration.copy(
            compilerIdentity = CompilerSymbolEvidenceDocument.fromSignature(signature).familyValue().identity
        )
    private val bodySignature =
        CompilerSignatureDocument.Function(
            base.text("anonymous@${existing.body.file.value}#40:80"),
            if (family == ScopeFamily.TOP_RUN) CompilerReceiverDocument.Absent
            else CompilerReceiverDocument.Present(base.text("kotlin/String")),
            base.bounded(emptyList()),
            base.bounded(emptyList()),
            CompilerTypeParameterCountDocument.parse(0).familyValue(),
        )
    private val body =
        existing.body.copy(
            compilerIdentity = CompilerSymbolEvidenceDocument.fromSignature(bodySignature).familyValue().identity
        )
    val declared =
        existing.declared.copy(
            body = body,
            contract =
                existing.declared.contract.copy(
                    target =
                        ImpactDeclaredCallbackTargetDocument(
                            target,
                            signature.qualifiedIdentity,
                            receiver,
                            signature.contextReceivers,
                            signature.valueParameters,
                            base.count(count.toLong()),
                        ),
                    position = position,
                ),
        )
    val binding =
        existing.binding.copy(
            rule =
                when (family) {
                    ScopeFamily.TOP_RUN -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_RUN_TOP_LEVEL_V1
                    ScopeFamily.RECEIVER_RUN -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_RUN_RECEIVER_V1
                    ScopeFamily.WITH -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_WITH_V1
                    ScopeFamily.APPLY -> ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_APPLY_V1
                },
            body = body,
            selected =
                ImpactReviewedFunctionDocument(
                    target,
                    signature.qualifiedIdentity,
                    receiver,
                    signature.contextReceivers,
                    signature.valueParameters,
                    base.count(count.toLong()),
                    position,
                ),
            artifacts =
                existing.artifacts.copy(
                    functionLines =
                        when (family) {
                            ScopeFamily.TOP_RUN -> lines(41, 46)
                            ScopeFamily.RECEIVER_RUN -> lines(55, 60)
                            ScopeFamily.WITH -> lines(69, 74)
                            ScopeFamily.APPLY -> lines(83, 89)
                        }
                ),
            invocationEvidence = ImpactReviewedInvocationEvidenceDocument.Declared(declared),
        )
    val returned =
        existing.transfer.copy(
            source = existing.source.copy(executionOwner = ImpactExecutionOwnerDocument.DeclaredCallback(declared)),
            binding =
                ImpactReviewedValueRelationshipDocument.fromBinding(
                    binding,
                    ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT,
                ),
        )
    val receiverProof =
        ImpactReviewedCallbackReceiverDocument(
            binding,
            declared,
            ImpactReceiverKindDocument.EXTENSION,
            base.text("kotlin/String"),
        )
    private val invocation = ImpactInvocationReferenceDocument(binding.supplierRange, binding.selected.declaration)
    val actual =
        existing.target.copy(
            range = existing.range(30, 35),
            role =
                if (family == ScopeFamily.WITH) ImpactValueRoleDocument.Argument(invocation, base.offset(0))
                else ImpactValueRoleDocument.Receiver(invocation, ImpactReceiverKindDocument.EXTENSION),
        )
    val input =
        ImpactReviewedValueTransferDocument(
            actual,
            actual.copy(
                range = body.range,
                role =
                    ImpactValueRoleDocument.Formal(
                        ImpactFormalPositionDocument.Receiver(ImpactReceiverKindDocument.EXTENSION)
                    ),
                executionOwner = ImpactExecutionOwnerDocument.DeclaredCallback(declared),
            ),
            ImpactReviewedValueRelationshipDocument(
                binding,
                ImpactReviewedValueRelationDocument.CallbackReceiverInput(receiverProof),
            ),
        )

    private fun lines(first: Int, last: Int) = ImpactReviewedSourceLinesDocument(base.offset(first), base.offset(last))
}

private fun <T, F> Refinement<T, F>.familyValue(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid scope family fixture: $failure")
    }
