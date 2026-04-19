package io.github.amichne.kast.shared.hierarchy

import com.intellij.psi.PsiElement
import io.github.amichne.kast.api.contract.Symbol

/** An edge in the type hierarchy — links a PSI declaration to its resolved [symbol]. */
data class TypeHierarchyEdge(
    val target: PsiElement,
    val symbol: Symbol,
)

/**
 * Backend-specific provider of supertype and subtype edges for a given PSI element.
 *
 * - **Standalone**: scans `session.allKtFiles()` for matching FQNs.
 * - **IntelliJ**: uses `DirectClassInheritorsSearch` and `JavaPsiFacade`.
 */
interface TypeEdgeResolver {
    /** Build the [Symbol] for this PSI element, including its supertype names when available. */
    fun symbolFor(target: PsiElement): Symbol
    fun supertypeEdges(target: PsiElement): List<TypeHierarchyEdge>
    fun subtypeEdges(target: PsiElement): List<TypeHierarchyEdge>
}
