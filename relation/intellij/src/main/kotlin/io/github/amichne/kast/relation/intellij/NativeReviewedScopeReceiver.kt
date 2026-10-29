package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedCallbackReceiver
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import org.jetbrains.kotlin.psi.KtFunctionLiteral

/** Compiler invocation and the actual anonymous receiver remain separate from reviewed return correspondence. */
internal fun nativeReviewedScopeReceiverInput(
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
    literal: KtFunctionLiteral,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
    val declared =
        when (val read = readDeclaredCallbackValueBinding(literal, source.enclosing, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    when (val permit = reviewedPermit(context)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return Refinement.Rejected(permit.failure.valueFlowCause())
    }
    val body = context.anonymous(literal)
    val receiver = body?.signature?.receiver as? CanonicalCompilerReceiver.Present
    if (body == null || receiver == null) return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    return when (
        val proof =
            ReviewedCallbackReceiver.fromCompiler(
                binding = binding,
                declared = declared,
                body = body,
                kind = ValueReceiverKind.EXTENSION,
                type = receiver.type,
            )
    ) {
        is Refinement.Refined -> ReviewedValueTransfer.callbackReceiverInput(source, proof.value)
        is Refinement.Rejected -> proof
    }
}
