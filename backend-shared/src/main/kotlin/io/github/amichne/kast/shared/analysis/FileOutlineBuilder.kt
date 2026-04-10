package io.github.amichne.kast.shared.analysis

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.amichne.kast.api.OutlineSymbol
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Builds a nested [OutlineSymbol] tree from a [KtFile]'s PSI declarations.
 *
 * Only collects class/object, function, and property declarations (not parameters or
 * anonymous elements). Nesting reflects the actual PSI parent-child structure: a method
 * inside a companion object inside a class becomes a deeply nested outline node.
 */
object FileOutlineBuilder {

    fun build(file: KtFile): List<OutlineSymbol> {
        val declarations = mutableListOf<Pair<KtNamedDeclaration, KtNamedDeclaration?>>()

        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is KtNamedDeclaration && isOutlineDeclaration(element) && !element.name.isNullOrBlank()) {
                    val parent = findNearestOutlineAncestor(element)
                    declarations += element to parent
                }
                super.visitElement(element)
            }
        })

        return buildTree(declarations, parent = null)
    }

    private fun isOutlineDeclaration(element: PsiElement): Boolean = when (element) {
        is KtClassOrObject,
        is KtNamedFunction,
        is KtProperty,
        -> true
        else -> false
    }

    private fun findNearestOutlineAncestor(element: KtNamedDeclaration): KtNamedDeclaration? {
        var current: PsiElement? = element.parent
        while (current != null && current !is KtFile) {
            if (current is KtNamedDeclaration && isOutlineDeclaration(current)) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun buildTree(
        declarations: List<Pair<KtNamedDeclaration, KtNamedDeclaration?>>,
        parent: KtNamedDeclaration?,
    ): List<OutlineSymbol> {
        return declarations
            .filter { (_, p) -> p === parent }
            .map { (decl, _) ->
                OutlineSymbol(
                    symbol = decl.toSymbolModel(containingDeclaration = parent?.fqName?.asString()),
                    children = buildTree(declarations, parent = decl),
                )
            }
    }
}
