package io.github.amichne.kast.shared.hierarchy

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.amichne.kast.api.CallDirection
import io.github.amichne.kast.api.CallNode
import io.github.amichne.kast.api.CallNodeTruncation
import io.github.amichne.kast.api.CallNodeTruncationReason
import io.github.amichne.kast.api.Location
import io.github.amichne.kast.api.Symbol
import io.github.amichne.kast.shared.analysis.resolvedFilePath
import io.github.amichne.kast.shared.analysis.toKastLocation
import io.github.amichne.kast.shared.analysis.toSymbolModel

/**
 * Backend-agnostic call hierarchy tree builder.
 *
 * Recursively expands a call graph using a [CallEdgeResolver] for edge
 * discovery. Does not depend on any backend-specific types (no standalone
 * session, no telemetry, no SQLite).
 */
class CallHierarchyEngine(
    private val edgeResolver: CallEdgeResolver,
) {

    /**
     * Recursively builds a [CallNode] tree for the given [target].
     */
    fun buildNode(
        target: PsiElement,
        parentCallSite: Location?,
        direction: CallDirection,
        depthRemaining: Int,
        pathKeys: Set<String>,
        budget: TraversalBudget,
        currentDepth: Int,
    ): CallNode {
        val symbol = target.toSymbolModel(containingDeclaration = null)
        val nodeKey = target.callHierarchySymbolIdentityKey(symbol)
        budget.recordNode(depth = currentDepth)

        if (depthRemaining == 0) {
            return CallNode(
                symbol = symbol,
                callSite = parentCallSite,
                children = emptyList(),
            )
        }

        if (budget.timeoutReached()) {
            val truncation = CallNodeTruncation(
                reason = CallNodeTruncationReason.TIMEOUT,
                details = "Traversal timeout reached before expanding children",
            )
            budget.recordTruncation()
            return CallNode(
                symbol = symbol,
                callSite = parentCallSite,
                truncation = truncation,
                children = emptyList(),
            )
        }

        val edges = findCallEdges(target, direction, budget)
        val children = mutableListOf<CallNode>()
        var truncation: CallNodeTruncation? = null

        for (edge in edges) {
            if (budget.timeoutReached()) {
                truncation = CallNodeTruncation(
                    reason = CallNodeTruncationReason.TIMEOUT,
                    details = "Traversal timeout reached while expanding children",
                )
                budget.timeoutHit = true
                break
            }
            if (budget.totalEdges >= budget.maxTotalCalls) {
                truncation = CallNodeTruncation(
                    reason = CallNodeTruncationReason.MAX_TOTAL_CALLS,
                    details = "Reached maxTotalCalls=${budget.maxTotalCalls}",
                )
                budget.maxTotalCallsHit = true
                break
            }
            if (children.size >= budget.maxChildrenPerNode) {
                truncation = CallNodeTruncation(
                    reason = CallNodeTruncationReason.MAX_CHILDREN_PER_NODE,
                    details = "Reached maxChildrenPerNode=${budget.maxChildrenPerNode}",
                )
                budget.maxChildrenHit = true
                break
            }

            budget.recordEdge()
            val childKey = edge.target.callHierarchySymbolIdentityKey(edge.symbol)
            val child = if (childKey == nodeKey || childKey in pathKeys) {
                budget.recordNode(depth = currentDepth + 1)
                budget.recordTruncation()
                CallNode(
                    symbol = edge.symbol,
                    callSite = edge.callSite,
                    truncation = CallNodeTruncation(
                        reason = CallNodeTruncationReason.CYCLE,
                        details = "Cycle detected for symbol=$childKey",
                    ),
                    children = emptyList(),
                )
            } else {
                buildNode(
                    target = edge.target,
                    parentCallSite = edge.callSite,
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

        return CallNode(
            symbol = symbol,
            callSite = parentCallSite,
            truncation = truncation,
            children = children,
        )
    }

    private fun findCallEdges(
        target: PsiElement,
        direction: CallDirection,
        budget: TraversalBudget,
    ): List<CallEdge> {
        val edges = when (direction) {
            CallDirection.INCOMING -> edgeResolver.incomingEdges(
                target = target,
                timeoutCheck = budget::timeoutReached,
                onFileVisited = budget::visitFile,
            )
            CallDirection.OUTGOING -> edgeResolver.outgoingEdges(
                target = target,
                timeoutCheck = budget::timeoutReached,
                onFileVisited = budget::visitFile,
            )
        }
        return edges.sortedWith(
            compareBy(
                { it.callSite.filePath },
                { it.callSite.startOffset },
                { it.callSite.endOffset },
                { it.symbol.fqName },
                { it.symbol.kind.name },
            ),
        )
    }
}

/**
 * Builds a unique identity key for a symbol at a specific location, used for
 * cycle detection during call hierarchy traversal.
 */
fun PsiElement.callHierarchySymbolIdentityKey(
    symbol: Symbol,
): String = buildString {
    append(symbol.fqName)
    append('|')
    append(resolvedFilePath().value)
    append(':')
    append(symbol.location.startOffset)
    append('-')
    append(symbol.location.endOffset)
}

/**
 * Converts a [com.intellij.psi.PsiReference] to a [Location] representing the
 * call site within the containing file.
 */
fun com.intellij.psi.PsiReference.callSiteLocation(): Location {
    val elementStart = element.textRange.startOffset
    return element.toKastLocation(
        TextRange(
            elementStart + rangeInElement.startOffset,
            elementStart + rangeInElement.endOffset,
        ),
    )
}
