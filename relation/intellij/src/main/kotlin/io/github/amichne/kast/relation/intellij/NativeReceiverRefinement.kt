@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaNonPublicApi::class,
)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaImplicitReceiverSmartCastKind
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSmartCastedReceiverValue
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.types.Variance

/** Called only after matching the resolved receiver to the tracked formal's compiler identity. */
internal fun KaSession.stableReceiverFieldProof(
    expression: KtNameReferenceExpression,
    receiver: KaSmartCastedReceiverValue,
    request: ValueFlowRequest,
    kind: ValueReceiverKind,
): NativeReceiverFieldProof {
    when (
        nativeCompilerTypeProof(listOf(receiver.original.type, receiver.type), request.budget.resources.workAllowance)
    ) {
        NativeCompilerTypeProof.ADMITTED -> Unit
        NativeCompilerTypeProof.WORK_LIMIT_REACHED ->
            return NativeReceiverFieldProof.Unsupported(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
        NativeCompilerTypeProof.ERROR_TYPE,
        NativeCompilerTypeProof.UNSUPPORTED_TYPE -> return unsupportedRefinement()
    }
    if (!stableFieldReceiver(expression, receiver)) return unsupportedRefinement()
    val original =
        when (val type = detachedReceiverType(receiver.original.type)) {
            is Refinement.Refined -> type.value
            is Refinement.Rejected -> return unsupportedRefinement()
        }
    val refined =
        when (val type = detachedReceiverType(receiver.type)) {
            is Refinement.Refined -> type.value
            is Refinement.Rejected -> return unsupportedRefinement()
        }
    val parent = expression.parent as? KtQualifiedExpression
    val use = if (parent?.selectorExpression === expression) parent else expression
    val condition =
        when (
            val fact =
                ValueCondition.StableReceiver.fromCompiler(
                    request.source,
                    sourceValueRange(use),
                    kind,
                    original,
                    refined,
                )
        ) {
            is Refinement.Refined -> fact.value
            is Refinement.Rejected -> return unsupportedRefinement()
        }
    return when (val conditions = request.source.conditions.requiring(condition)) {
        is Refinement.Refined -> NativeReceiverFieldProof.Matched(conditions.value)
        is Refinement.Rejected -> unsupportedRefinement()
    }
}

private fun KaSession.stableFieldReceiver(
    expression: KtNameReferenceExpression,
    receiver: KaSmartCastedReceiverValue,
): Boolean =
    when (val original = receiver.original) {
        is KaExplicitReceiverValue -> {
            val fact = original.expression.smartCastInfo
            fact != null &&
                fact.isStable &&
                fact.smartCastType.semanticallyEquals(receiver.type) &&
                fact.originalType.semanticallyEquals(original.type)
        }
        is KaImplicitReceiverValue -> {
            // This is the property's dispatch slot, not the enclosing function's receiver slot.
            // The pinned provider emits implicit facts only for stable smartcasts.
            val fact =
                expression.implicitReceiverSmartCasts.singleOrNull {
                    it.kind == KaImplicitReceiverSmartCastKind.DISPATCH
                }
            fact != null && fact.type.semanticallyEquals(receiver.type)
        }
        is KaSmartCastedReceiverValue -> false
    }

private fun KaSession.detachedReceiverType(type: KaType) =
    CanonicalCompilerType.fromCompiler(type.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, Variance.INVARIANT))

private fun unsupportedRefinement() =
    NativeReceiverFieldProof.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
