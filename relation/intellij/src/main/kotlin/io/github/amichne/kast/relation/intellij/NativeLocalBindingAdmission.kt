package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueRole
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

internal fun PsiElement.isNativeReferenceBinding(role: ValueRole): Boolean =
    when (role) {
        ValueRole.LocalBinding ->
            when (val binding = nativeLocalBindingMutability()) {
                is Refinement.Refined -> binding.value == NativeLocalBindingMutability.IMMUTABLE
                is Refinement.Rejected -> false
            }
        is ValueRole.Formal -> role.position is ValueFormalPosition.Parameter && this is KtParameter
        is ValueRole.Argument,
        is ValueRole.Receiver,
        is ValueRole.LoopResult,
        ValueRole.ExpressionResult,
        ValueRole.LocalRead,
        ValueRole.Return,
        ValueRole.PropertyAssignment -> false
    }

internal enum class NativeLocalBindingMutability {
    IMMUTABLE,
    MUTABLE,
}

/** The same cheap declaration law governs admission and reference enumeration. */
internal fun PsiElement.nativeLocalBindingMutability():
    Refinement<NativeLocalBindingMutability, ValueFlowUnsupportedCause> {
    val mutable =
        when (this) {
            is KtProperty -> {
                if (!isLocal)
                    return Refinement.Rejected(
                        if (isVar) ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW
                        else ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY
                    )
                isVar
            }
            is KtDestructuringDeclarationEntry -> {
                val declaration =
                    parent as? KtDestructuringDeclaration
                        ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                if (name == "_" || declaration.nativeDestructuringContext() == NativeDestructuringContext.Unsupported)
                    return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                declaration.isVar
            }
            else -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    return Refinement.Refined(
        if (mutable) NativeLocalBindingMutability.MUTABLE else NativeLocalBindingMutability.IMMUTABLE
    )
}

internal fun PsiElement.nativeLocalBindingCause(): ValueFlowUnsupportedCause =
    when (val binding = nativeLocalBindingMutability()) {
        is Refinement.Refined ->
            when (binding.value) {
                NativeLocalBindingMutability.MUTABLE -> ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW
                NativeLocalBindingMutability.IMMUTABLE ->
                    error("Immutable local binding must use the production reference scan")
            }
        is Refinement.Rejected -> binding.failure
    }
