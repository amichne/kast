package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/** Shared K2 identity proof for local value reads; spelling never establishes a transfer. */
internal enum class LocalBindingReferenceConfirmation {
    EXACT_BINDING,
    OTHER_BINDING,
    UNRESOLVED,
}

internal fun confirmLocalBindingReference(
    reference: KtReference,
    property: KtNamedDeclaration,
    observation: IntellijReadObservation,
): LocalBindingReferenceConfirmation {
    return when (val resolution = resolveLocalValueReference(reference, observation)) {
        NativeLocalValueReferenceResolution.Unresolved -> LocalBindingReferenceConfirmation.UNRESOLVED
        is NativeLocalValueReferenceResolution.Resolved ->
            if (resolution.declaration === property) LocalBindingReferenceConfirmation.EXACT_BINDING
            else LocalBindingReferenceConfirmation.OTHER_BINDING
    }
}

internal sealed interface NativeLocalValueReferenceResolution {
    data class Resolved(val declaration: PsiElement) : NativeLocalValueReferenceResolution

    data object Unresolved : NativeLocalValueReferenceResolution
}

/** Shared native observation, including formal references; consumers retain their own ownership and role checks. */
internal fun resolveLocalValueReference(
    reference: KtReference,
    observation: IntellijReadObservation,
): NativeLocalValueReferenceResolution {
    val declaration =
        observation.observedAnalyze(reference.element) { reference.resolveToSymbol()?.psi }
            ?: return NativeLocalValueReferenceResolution.Unresolved
    return NativeLocalValueReferenceResolution.Resolved(declaration)
}
