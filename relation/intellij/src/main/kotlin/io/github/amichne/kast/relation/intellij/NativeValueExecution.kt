package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRejection

internal data class NativeValueExecution(val nativeOwner: PsiElement, val owner: ValueExecutionOwner)

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
