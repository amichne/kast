package io.github.amichne.kast.shared.hierarchy

import com.intellij.psi.PsiElement
import io.github.amichne.kast.api.contract.Symbol

/**
 * Indexer provider of supertype and subtype edges for a given PSI element.
 *
 * The implementation uses `DirectClassInheritorsSearch` and `JavaPsiFacade`
 * inside the isolated indexer.
 */
interface TypeEdgeResolver {
    /** Build the [Symbol] for this PSI element, including its supertype names when available. */
    fun symbolFor(target: PsiElement): Symbol
    fun supertypeEdges(target: PsiElement, budget: EdgeDiscoveryBudget): List<TypeHierarchyEdge>
    fun subtypeEdges(target: PsiElement, budget: EdgeDiscoveryBudget): List<TypeHierarchyEdge>
}
