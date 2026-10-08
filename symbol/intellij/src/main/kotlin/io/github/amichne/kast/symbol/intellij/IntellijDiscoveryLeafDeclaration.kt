package io.github.amichne.kast.symbol.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias

/** Starts at a reacquired leaf, never walks the previously consumed semantic prefix. */
internal fun PsiElement.discoveryDeclaration(request: SymbolDiscoveryRequest): KtNamedDeclaration? {
    var element: PsiElement? = this
    while (element != null && element !is KtFile) {
        val declaration = element as? KtNamedDeclaration
        if (declaration != null && declaration.textRange.startOffset == textRange.startOffset) {
            val kind = declaration.discoveryCompilerKind()
            if (kind != null && kind in request.requestedDeclarationKinds()) return declaration
        }
        element = element.parent
    }
    return null
}

internal fun KtNamedDeclaration.discoveryCompilerKind(): CompilerSymbolKind? =
    when (this) {
        is KtEnumEntry -> null
        is KtClassOrObject -> CompilerSymbolKind.CLASSLIKE
        is KtNamedFunction -> CompilerSymbolKind.FUNCTION
        is KtProperty -> CompilerSymbolKind.PROPERTY
        is KtTypeAlias -> CompilerSymbolKind.TYPE_ALIAS
        is KtParameter -> if (hasValOrVar()) CompilerSymbolKind.PROPERTY else null
        else -> null
    }
