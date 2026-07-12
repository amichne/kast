package io.github.amichne.kast.shared.hierarchy

import com.intellij.psi.PsiElement
import io.github.amichne.kast.api.contract.Symbol

/** An edge in the type hierarchy — links a PSI declaration to its resolved [symbol]. */
data class TypeHierarchyEdge(
    val target: PsiElement,
    val symbol: Symbol,
)
