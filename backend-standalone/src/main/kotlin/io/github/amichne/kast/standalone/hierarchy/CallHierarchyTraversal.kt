package io.github.amichne.kast.standalone.hierarchy

import io.github.amichne.kast.api.contract.query.CallHierarchyQuery
import io.github.amichne.kast.api.contract.result.CallHierarchyResult
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.shared.analysis.resolveTarget
import io.github.amichne.kast.shared.hierarchy.CallHierarchyEngine
import io.github.amichne.kast.shared.hierarchy.TraversalBudget
import io.github.amichne.kast.standalone.StandaloneAnalysisSession
import io.github.amichne.kast.standalone.analysis.CandidateFileResolver
import io.github.amichne.kast.standalone.normalizeStandalonePath
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetry
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryScope
import java.nio.file.Path

internal class CallHierarchyTraversal(
    private val workspaceRoot: Path,
    private val limits: ServerLimits,
    private val session: StandaloneAnalysisSession,
    private val telemetry: StandaloneTelemetry,
) {
    private val normalizedWorkspaceRoot = normalizeStandalonePath(workspaceRoot)
    private val candidateFileResolver = CandidateFileResolver(session = session)

    fun build(query: CallHierarchyQuery): CallHierarchyResult {
        val file = session.findKtFile(query.position.filePath)
        val rootTarget = resolveTarget(file, query.position.offset)

        return telemetry.inSpan(
            scope = StandaloneTelemetryScope.CALL_HIERARCHY,
            name = "kast.callHierarchy",
            attributes = mapOf(
                "kast.callHierarchy.direction" to query.direction.name,
                "kast.callHierarchy.depth" to query.depth,
                "kast.callHierarchy.maxTotalCalls" to query.maxTotalCalls,
                "kast.callHierarchy.maxChildrenPerNode" to query.maxChildrenPerNode,
                "kast.callHierarchy.timeoutMillis" to (query.timeoutMillis ?: limits.requestTimeoutMillis),
            ),
        ) { span ->
            val budget = TraversalBudget(
                maxTotalCalls = query.maxTotalCalls,
                maxChildrenPerNode = query.maxChildrenPerNode,
                timeoutMillis = query.timeoutMillis ?: limits.requestTimeoutMillis,
            )
            val resolver = StandaloneCallEdgeResolver(
                candidateFileResolver = candidateFileResolver,
                normalizedWorkspaceRoot = normalizedWorkspaceRoot,
            )
            val engine = CallHierarchyEngine(edgeResolver = resolver)
            val root = engine.buildNode(
                target = rootTarget,
                parentCallSite = null,
                direction = query.direction,
                depthRemaining = query.depth,
                pathKeys = emptySet(),
                budget = budget,
                currentDepth = 0,
            )
            val stats = budget.toStats()

            span.setAttribute("kast.callHierarchy.totalNodes", stats.totalNodes)
            span.setAttribute("kast.callHierarchy.totalEdges", stats.totalEdges)
            span.setAttribute("kast.callHierarchy.truncatedNodes", stats.truncatedNodes)
            span.setAttribute("kast.callHierarchy.filesVisited", stats.filesVisited)
            span.setAttribute("kast.callHierarchy.timeoutReached", stats.timeoutReached)
            span.setAttribute("kast.callHierarchy.maxDepthReached", stats.maxDepthReached)

            CallHierarchyResult(
                root = root,
                stats = stats,
            )
        }
    }
}
