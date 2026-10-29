package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Independent wire oracle for the reviewed Kotlin 2.3.0 let source and compiler invocation contract. */
internal class ImpactReviewedScopeFixture {
    val base = ImpactFindingFixture()
    private val signature =
        CompilerSignatureDocument.Function(
            base.text("kotlin.let"),
            CompilerReceiverDocument.Present(base.text("T")),
            base.bounded(emptyList()),
            base.bounded(listOf(base.text("(T)->R"))),
            CompilerTypeParameterCountDocument.parse(2).scopeValue(),
        )
    val declaration =
        base.declaration.copy(
            file = base.text("jar:///library/kotlin-stdlib-2.3.0.jar!/kotlin/StandardKt.class"),
            compilerIdentity = CompilerSymbolEvidenceDocument.fromSignature(signature).scopeValue().identity,
        )
    val body =
        ImpactCapturedBodyDocument(
            base.declaration.file,
            range(40, 80),
            base.text("canonical-signature-sha256-v1|" + "c".repeat(64)),
        )
    val declared =
        ImpactDeclaredCallbackValueBindingDocument(
            base.declaration,
            body,
            ImpactCallbackDependencyContractDocument(
                base.declaration.basis,
                base.declaration.file,
                range(30, 90),
                ImpactCapturedBodyDocument(
                    base.declaration.file,
                    base.declaration.range,
                    base.declaration.compilerIdentity,
                ),
                ImpactDeclaredCallbackTargetDocument(
                    declaration,
                    signature.qualifiedIdentity,
                    ImpactDeclaredCallbackReceiverDocument.Present(base.text("T")),
                    signature.contextReceivers,
                    signature.valueParameters,
                    base.count(2),
                ),
                base.offset(0),
                base.text("f7d71cc1a37a29843d8e3a0b660afa358bdf0f8ac3ffb566a0e79c004f7ffd05"),
                QueryCallbackDependencyContractProvenanceDocument.KOTLIN_BINARY_CONTRACT,
                QueryCallbackDependencyInvocationKindDocument.EXACTLY_ONCE,
            ),
            ImpactDeclaredCallbackResultEffectDocument.UNSPECIFIED,
        )
    val artifacts =
        ImpactReviewedScopeArtifactsDocument(
            base.text("887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1"),
            base.text("326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531"),
            base.text("86b7320d868d66492d277ebb5d5d8ad78da074d592dacf32f186a83d8d3196a0"),
            base.text("f7d71cc1a37a29843d8e3a0b660afa358bdf0f8ac3ffb566a0e79c004f7ffd05"),
            base.text("bfdad3872af89a51041163c03afda592baea4e394a8b1665de5b7547f4f43ce0"),
            ImpactReviewedSourceLinesDocument(base.offset(114), base.offset(119)),
        )
    val binding =
        ImpactReviewedValueBindingDocument(
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_LET_V1,
            base.declaration.basis,
            base.declaration,
            body,
            declared.contract.occurrenceRange,
            ImpactReviewedFunctionDocument(
                declaration,
                signature.qualifiedIdentity,
                base.text("T"),
                signature.contextReceivers,
                signature.valueParameters,
                base.count(2),
                base.offset(0),
            ),
            artifacts,
            ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT,
            ImpactActualExecutionDocument.NOT_ESTABLISHED,
            ImpactInvocationCountKnowledgeDocument.NOT_ESTABLISHED,
            ImpactReviewedInvocationEvidenceDocument.Declared(declared),
        )
    private val path =
        base.bounded(
            listOf(
                ImpactValuePathSegmentDocument.Field(base.declaration.copy(range = range(5, 15))),
                ImpactValuePathSegmentDocument.Element,
            )
        )
    val source =
        ImpactValueSiteReferenceDocument(
            base.declaration,
            range(50, 60),
            ImpactValueRoleDocument.Return,
            ImpactExecutionOwnerDocument.DeclaredCallback(declared),
            path,
        )
    val target =
        source.copy(
            range = declared.contract.occurrenceRange,
            role = ImpactValueRoleDocument.ExpressionResult,
            executionOwner = ImpactExecutionOwnerDocument.Declaration,
        )
    val transfer =
        ImpactReviewedValueTransferDocument(
            source,
            target,
            ImpactReviewedValueRelationshipDocument.fromBinding(binding, binding.semantics),
        )

    fun range(start: Int, end: Int) = ImpactSourceRangeDocument(base.offset(start), base.offset(end))
}

private fun <T, F> Refinement<T, F>.scopeValue(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid scope document fixture: $failure")
    }
