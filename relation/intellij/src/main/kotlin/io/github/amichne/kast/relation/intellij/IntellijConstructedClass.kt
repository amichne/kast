package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtConstructor

/** The constructed class a constructor call names, as K2 reports it, or why that owner is not proven. */
internal sealed interface IntellijConstructedClass {
    data class Found(val declaration: PsiNamedElement) : IntellijConstructedClass

    data object NotConstructor : IntellijConstructedClass

    data object Unresolved : IntellijConstructedClass
}

/**
 * A constructor call such as `Name(...)` names its class. K2 resolves the call to the constructor, so exact equality
 * with a classlike subject fails although the occurrence references that class. Only a reference whose resolved
 * declaration is a constructor reaches K2 again. K2 supplies the constructor's containing class, and the ordinary exact
 * declaration proof then compares that class with the subject. An unproved owner stays unresolved, never different.
 */
internal fun IntellijK2RelationProjection.confirmConstructedClassReference(
    reference: KtReference,
    resolved: PsiNamedElement,
    subject: RelationEndpoint,
): IntellijReferenceTargetResult? {
    if (resolved !is KtConstructor<*> && (resolved as? PsiMethod)?.isConstructor != true) return null
    return when (val owner = constructedClass(reference)) {
        IntellijConstructedClass.NotConstructor -> null
        IntellijConstructedClass.Unresolved -> IntellijReferenceTargetResult.Unresolved
        is IntellijConstructedClass.Found -> confirmDeclaration(owner.declaration, subject)
    }
}

private fun IntellijK2RelationProjection.constructedClass(reference: KtReference): IntellijConstructedClass =
    observation.observedAnalyze(reference.element) {
        val constructor =
            reference.resolveToSymbol() as? KaConstructorSymbol
                ?: return@observedAnalyze IntellijConstructedClass.NotConstructor
        val owner =
            constructor.containingSymbol as? KaClassSymbol
                ?: return@observedAnalyze unresolvedOwner(IntellijReadTermination.K2_CALLABLE_CONTAINER_UNSUPPORTED)
        when (val declaration = owner.psi) {
            null -> unresolvedOwner(IntellijReadTermination.K2_SYMBOL_WITHOUT_PSI)
            is PsiNamedElement -> IntellijConstructedClass.Found(declaration)
            else -> unresolvedOwner(IntellijReadTermination.K2_NON_KOTLIN_PSI)
        }
    }

private fun IntellijK2RelationProjection.unresolvedOwner(reason: IntellijReadTermination): IntellijConstructedClass {
    observation.terminated(reason)
    return IntellijConstructedClass.Unresolved
}
