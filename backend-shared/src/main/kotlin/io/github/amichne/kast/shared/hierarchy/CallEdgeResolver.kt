package io.github.amichne.kast.shared.hierarchy

import com.intellij.psi.PsiElement
import io.github.amichne.kast.api.Location
import io.github.amichne.kast.api.Symbol

/**
 * Represents a single edge in the call hierarchy: a resolved target declaration
 * plus the call-site location where the reference occurs.
 */
data class CallEdge(
    val target: PsiElement,
    val symbol: Symbol,
    val callSite: Location,
)

/**
 * Backend-agnostic strategy for discovering incoming and outgoing call edges.
 *
 * Each backend (standalone, IntelliJ plugin) provides its own implementation
 * using its native reference-search infrastructure.
 */
interface CallEdgeResolver {

    /**
     * Returns all declarations that call [target].
     *
     * @param onFileVisited called for each file scanned, even when it yields no edges
     */
    fun incomingEdges(
        target: PsiElement,
        timeoutCheck: () -> Boolean,
        onFileVisited: (filePath: String) -> Unit,
    ): List<CallEdge>

    /**
     * Returns all declarations called by [target].
     *
     * @param onFileVisited called for each file scanned, even when it yields no edges
     */
    fun outgoingEdges(
        target: PsiElement,
        timeoutCheck: () -> Boolean,
        onFileVisited: (filePath: String) -> Unit,
    ): List<CallEdge>
}
