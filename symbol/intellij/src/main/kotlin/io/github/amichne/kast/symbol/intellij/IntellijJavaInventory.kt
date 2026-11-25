package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiAnnotationMethod
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiTypeParameter
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest

internal val JAVA_DECLARATION_KINDS =
    setOf(CompilerSymbolKind.CLASSLIKE, CompilerSymbolKind.FUNCTION, CompilerSymbolKind.PROPERTY)

/** Starts at a reacquired leaf, as the Kotlin inventory does, and never reads the consumed prefix again. */
internal fun PsiElement.javaInventoryDeclaration(request: SymbolDiscoveryRequest): IntellijInventoryDeclaration {
    val start = textRange.startOffset
    val member =
        generateSequence(this) { it.parent }
            .takeWhile { it !is PsiFile }
            .filterIsInstance<PsiMember>()
            .takeWhile { it.textRange.startOffset == start }
            .firstOrNull { declaration ->
                declaration.inventoryFamilies().any { it in request.requestedDeclarationKinds() }
            }
    return when {
        member == null -> IntellijInventoryDeclaration.None
        (member as? PsiNamedElement)?.javaLocationKind() in request.requestedDeclarationKinds() ->
            IntellijInventoryDeclaration.Selected(member as NavigationItem)
        else -> IntellijInventoryDeclaration.Unselectable
    }
}

/**
 * The kinds in which Kotlin can use a Java member. A constructor counts through its class, and an inventory excludes
 * enum entries. A record component has no member of its own, so its record stands for the property and its accessor.
 */
private fun PsiMember.inventoryFamilies(): Set<CompilerSymbolKind> =
    when (this) {
        is PsiTypeParameter,
        is PsiEnumConstant -> emptySet()
        is PsiClass -> if (isRecord) JAVA_DECLARATION_KINDS else setOf(CompilerSymbolKind.CLASSLIKE)
        is PsiAnnotationMethod -> setOf(CompilerSymbolKind.FUNCTION, CompilerSymbolKind.PROPERTY)
        is PsiMethod -> if (isConstructor) emptySet() else setOf(CompilerSymbolKind.FUNCTION)
        is PsiField -> setOf(CompilerSymbolKind.PROPERTY)
        else -> emptySet()
    }
