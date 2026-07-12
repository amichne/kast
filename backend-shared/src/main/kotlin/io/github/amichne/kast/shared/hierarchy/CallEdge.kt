package io.github.amichne.kast.shared.hierarchy

import com.intellij.psi.PsiElement
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.Symbol

/**
 * Represents a single edge in the call hierarchy: a resolved target declaration
 * plus the call-site location where the reference occurs.
 */
data class CallEdge(
    val target: PsiElement,
    val symbol: Symbol,
    val callSite: Location,
)
