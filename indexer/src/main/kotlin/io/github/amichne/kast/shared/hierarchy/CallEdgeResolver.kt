package io.github.amichne.kast.shared.hierarchy

import com.intellij.psi.PsiElement

/**
 * Strategy for discovering incoming and outgoing call edges through the
 * indexer's reference-search infrastructure.
 */
interface CallEdgeResolver {

    /**
     * Returns all declarations that call [target].
     *
     * @param onFileVisited called once per unique file examined during the search,
     *        regardless of whether it yields edges. Implementations must deduplicate.
     */
    fun incomingEdges(
        target: PsiElement,
        budget: EdgeDiscoveryBudget,
        onFileVisited: (filePath: String) -> Unit,
    ): List<CallEdge>

    /**
     * Returns all declarations called by [target].
     *
     * @param onFileVisited called once per unique file examined during the search,
     *        regardless of whether it yields edges. Implementations must deduplicate.
     */
    fun outgoingEdges(
        target: PsiElement,
        budget: EdgeDiscoveryBudget,
        onFileVisited: (filePath: String) -> Unit,
    ): List<CallEdge>
}
