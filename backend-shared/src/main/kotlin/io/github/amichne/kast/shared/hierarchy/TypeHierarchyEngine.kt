package io.github.amichne.kast.shared.hierarchy

import com.intellij.psi.PsiElement
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.result.TypeHierarchyNode
import io.github.amichne.kast.api.contract.result.TypeHierarchyStats
import io.github.amichne.kast.api.contract.result.TypeHierarchyTruncation
import io.github.amichne.kast.api.contract.result.TypeHierarchyTruncationReason
import io.github.amichne.kast.shared.analysis.resolvedFilePath

/**
 * Backend-agnostic type hierarchy tree builder.
 *
 * Recursively expands a type graph using a [TypeEdgeResolver] for edge discovery.
 * Does not depend on any backend-specific types (no standalone session, no telemetry).
 *
 * @param readAccess wraps PSI access between edge-resolver calls. The IntelliJ plugin
 *        backend supplies `runReadAction`; standalone can pass [ReadAccessScope.IDENTITY].
 */
class TypeHierarchyEngine(
    private val edgeResolver: TypeEdgeResolver,
    private val readAccess: ReadAccessScope = ReadAccessScope.IDENTITY,
) {

    fun buildNode(
        target: PsiElement,
        direction: TypeHierarchyDirection,
        depthRemaining: Int,
        pathKeys: Set<String>,
        budget: TypeHierarchyBudget,
        currentDepth: Int,
    ): TypeHierarchyNode {
        val (symbol, nodeKey) = readAccess.run {
            val s = edgeResolver.symbolFor(target)
            s to target.typeHierarchySymbolIdentityKey(s)
        }
        budget.recordNode(depth = currentDepth)

        if (depthRemaining == 0 || !target.isTypeHierarchyExpandable()) {
            return TypeHierarchyNode(symbol = symbol, children = emptyList())
        }

        val edges = findEdges(target, direction)
        val children = mutableListOf<TypeHierarchyNode>()
        var truncation: TypeHierarchyTruncation? = null

        for (edge in edges) {
            if (budget.totalNodes >= budget.maxResults) {
                truncation = TypeHierarchyTruncation(
                    reason = TypeHierarchyTruncationReason.MAX_RESULTS,
                    details = "Reached maxResults=${budget.maxResults}",
                )
                break
            }

            val childKey = readAccess.run { edge.target.typeHierarchySymbolIdentityKey(edge.symbol) }
            val child = if (childKey == nodeKey || childKey in pathKeys) {
                budget.recordNode(depth = currentDepth + 1)
                budget.recordTruncation()
                TypeHierarchyNode(
                    symbol = edge.symbol,
                    truncation = TypeHierarchyTruncation(
                        reason = TypeHierarchyTruncationReason.CYCLE,
                        details = "Cycle detected for symbol=$childKey",
                    ),
                    children = emptyList(),
                )
            } else {
                buildNode(
                    target = edge.target,
                    direction = direction,
                    depthRemaining = depthRemaining - 1,
                    pathKeys = pathKeys + nodeKey,
                    budget = budget,
                    currentDepth = currentDepth + 1,
                )
            }
            children += child
        }

        if (truncation != null) {
            budget.recordTruncation()
        }

        return TypeHierarchyNode(symbol = symbol, truncation = truncation, children = children)
    }

    private fun findEdges(
        target: PsiElement,
        direction: TypeHierarchyDirection,
    ): List<TypeHierarchyEdge> {
        val edges = when (direction) {
            TypeHierarchyDirection.SUPERTYPES -> edgeResolver.supertypeEdges(target)
            TypeHierarchyDirection.SUBTYPES -> edgeResolver.subtypeEdges(target)
            TypeHierarchyDirection.BOTH -> edgeResolver.supertypeEdges(target) + edgeResolver.subtypeEdges(target)
        }
        return edges
            .distinctBy { edge -> readAccess.run { edge.target.typeHierarchySymbolIdentityKey(edge.symbol) } }
            .sortedWith(
                compareBy(
                    { it.symbol.fqName },
                    { it.symbol.kind.name },
                    { it.symbol.location.filePath },
                    { it.symbol.location.startOffset },
                ),
            )
    }
}

/** Budget tracker for type hierarchy traversal. */
class TypeHierarchyBudget(
    val maxResults: Int,
) {
    var totalNodes: Int = 0
        private set
    var maxDepthReached: Int = 0
        private set
    var truncated: Boolean = false
        private set

    fun recordNode(depth: Int) {
        totalNodes += 1
        if (depth > maxDepthReached) {
            maxDepthReached = depth
        }
    }

    fun recordTruncation() {
        truncated = true
    }

    fun toStats() = TypeHierarchyStats(
        totalNodes = totalNodes,
        maxDepthReached = maxDepthReached,
        truncated = truncated,
    )
}

fun PsiElement.typeHierarchySymbolIdentityKey(symbol: Symbol): String = buildString {
    append(symbol.fqName)
    append('|')
    append(resolvedFilePath().value)
    append(':')
    append(symbol.location.startOffset)
    append('-')
    append(symbol.location.endOffset)
}

fun PsiElement.isTypeHierarchyExpandable(): Boolean =
    this is org.jetbrains.kotlin.psi.KtClassOrObject || this is com.intellij.psi.PsiClass
