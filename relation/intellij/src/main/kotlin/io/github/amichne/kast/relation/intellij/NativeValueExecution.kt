package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRejection

internal data class NativeValueExecution(val nativeOwner: PsiElement, val owner: ValueExecutionOwner)

/** Restore only a retained, admitted capture on the identical semantic basis. New lexical seeds stay rejected. */
internal fun restoreCapturedValueExecution(
    element: PsiElement,
    host: PsiElement,
    expected: ValueExecutionOwner,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<NativeValueExecution, ValueFlowRejection> {
    val body =
        when (val context = expected.context) {
            io.github.amichne.kast.relation.contract.ValueExecutionContext.Declaration ->
                return nativeValueExecutionOwner(element, host, expected.enclosing)
            is io.github.amichne.kast.relation.contract.ValueExecutionContext.Callback -> context.body
            is io.github.amichne.kast.relation.contract.ValueExecutionContext.ReviewedCallback -> context.binding.body
        }
    if (expected.basis != scope.request.subject.lease.identity)
        return Refinement.Rejected(ValueFlowRejection.STALE_SITE)
    var current: PsiElement? = element
    while (current != null && current !== host) {
        if (current is org.jetbrains.kotlin.psi.KtFunction && sourceValueRange(current) == body.range) {
            val reader =
                IntellijCallbackFlowContext(
                    scope,
                    projection,
                    { CallbackWorkAdmission.READY },
                    projection.observation,
                    { CallbackExpiryAdmission.CURRENT },
                )
            return if (
                reader.anonymous(current) == body &&
                    valueNativeOwnership(element, current) == NativeValueOwnership.ADMITTED
            )
                Refinement.Refined(NativeValueExecution(current, expected))
            else Refinement.Rejected(ValueFlowRejection.STALE_SITE)
        }
        current = current.parent
    }
    return Refinement.Rejected(ValueFlowRejection.STALE_SITE)
}

/** Plain source values retain the existing rejection of nested execution bodies. */
internal fun nativeValueExecutionOwner(
    element: PsiElement,
    host: PsiElement,
    enclosing: RelationEndpoint,
): Refinement<NativeValueExecution, ValueFlowRejection> =
    when (valueNativeOwnership(element, host)) {
        NativeValueOwnership.ADMITTED ->
            Refinement.Refined(NativeValueExecution(host, ValueExecutionOwner.declaration(enclosing)))
        NativeValueOwnership.NESTED -> Refinement.Rejected(ValueFlowRejection.NESTED_EXECUTION)
        NativeValueOwnership.UNAVAILABLE -> Refinement.Rejected(ValueFlowRejection.OWNER_UNAVAILABLE)
    }
