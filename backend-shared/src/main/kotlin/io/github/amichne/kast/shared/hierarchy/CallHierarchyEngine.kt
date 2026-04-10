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
 *
 * @param readAccess wraps any direct PSI access that occurs between edge-resolver
 *        calls. The IntelliJ plugin backend supplies `runReadAction`; the standalone
 *        backend can pass the identity function since it already holds a session-level
 *        read lock.
 */
class CallHierarchyEngine(
    private val edgeResolver: CallEdgeResolver,
    private val readAccess: ReadAccessScope = ReadAccessScope.IDENTITY,
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
        val (symbol, nodeKey) = readAccess.run {
            val s = target.toSymbolModel(containingDeclaration = null)
            s to target.callHierarchySymbolIdentityKey(s)
        }
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
            val childKey = readAccess.run { edge.target.callHierarchySymbolIdentityKey(edge.symbol) }
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
 * Abstraction for acquiring a read lock around PSI access.
 *
 * - IntelliJ plugin backend: delegates to `ApplicationManager.getApplication().runReadAction`.
 * - Standalone backend: identity (session-level read lock is already held).
 */
interface ReadAccessScope {
    fun <T> run(action: () -> T): T

    companion object {
        /** Identity implementation — executes the action directly without acquiring any lock. */
        val IDENTITY: ReadAccessScope = object : ReadAccessScope {
            override fun <T> run(action: () -> T): T = action()
        }
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
