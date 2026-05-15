package io.github.amichne.kast.standalone.hierarchy

import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import io.github.amichne.kast.api.validation.ParsedTypeHierarchyQuery
import io.github.amichne.kast.shared.analysis.resolveTarget
import io.github.amichne.kast.shared.analysis.typeHierarchyDeclaration
import io.github.amichne.kast.shared.hierarchy.ReadAccessScope
import io.github.amichne.kast.shared.hierarchy.TypeHierarchyBudget
import io.github.amichne.kast.shared.hierarchy.TypeHierarchyEngine
import io.github.amichne.kast.standalone.StandaloneAnalysisSession

internal class TypeHierarchyTraversal(
    private val session: StandaloneAnalysisSession,
) {
    private val resolver = StandaloneTypeEdgeResolver(session)
    private val engine = TypeHierarchyEngine(edgeResolver = resolver, readAccess = ReadAccessScope.IDENTITY)

    fun build(query: ParsedTypeHierarchyQuery): TypeHierarchyResult {
        val file = session.findKtFile(query.position.filePath.value)
        val resolvedTarget = resolveTarget(file, query.position.offset.value)
        val rootTarget = resolvedTarget.typeHierarchyDeclaration() ?: resolvedTarget
        val budget = TypeHierarchyBudget(maxResults = query.maxResults.value)
        val root = engine.buildNode(
            target = rootTarget,
            direction = query.direction,
            depthRemaining = query.depth.value,
            pathKeys = emptySet(),
            budget = budget,
            currentDepth = 0,
        )
        return TypeHierarchyResult(
            root = root,
            stats = budget.toStats(),
        )
    }
}
