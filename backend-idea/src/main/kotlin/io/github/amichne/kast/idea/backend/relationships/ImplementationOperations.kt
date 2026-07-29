@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.relationships

import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiElement
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.result.ImplementationRelation
import io.github.amichne.kast.api.contract.result.ImplementationRelationsResult
import io.github.amichne.kast.api.contract.result.ImplementationsResult
import io.github.amichne.kast.api.contract.result.RelationTraversalHandle
import io.github.amichne.kast.api.contract.skill.KastImplementationsQuery
import io.github.amichne.kast.api.validation.ParsedImplementationsQuery
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.IdeaTelemetryScope
import io.github.amichne.kast.idea.IdeaTypeEdgeResolver
import io.github.amichne.kast.idea.RelationshipContinuationStore
import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.idea.timedReadAction
import io.github.amichne.kast.shared.analysis.resolveTarget
import io.github.amichne.kast.shared.analysis.typeHierarchyDeclaration
import io.github.amichne.kast.shared.hierarchy.EdgeDiscoveryBudget
import io.github.amichne.kast.shared.hierarchy.EdgeDiscoveryCompletion
import kotlinx.coroutines.withContext

internal suspend fun KastPluginBackend.implementationsOperation(
    query: ParsedImplementationsQuery,
): ImplementationsResult = withContext(readDispatcher) {
    telemetry.inSpan(IdeaTelemetryScope.IMPLEMENTATIONS, "kast.idea.implementations") {
        val rootTarget = readAction {
            val file = findKtFile(query.position.filePath.value)
            val resolved = resolveTarget(file, query.position.offset.value)
            resolved.typeHierarchyDeclaration() ?: resolved
        }
        val resolver = IdeaTypeEdgeResolver(project = project)
        val declarationSymbol = resolver.symbolFor(rootTarget)
        val queue = ArrayDeque<PsiElement>()
        val visited = mutableSetOf<String>()
        val implementations = mutableListOf<Symbol>()
        queue += rootTarget
        var exhaustive = true
        val limit = query.maxResults.value
        val providerBudget = EdgeDiscoveryBudget(
            maxCandidates = KastPluginBackend.RELATIONSHIP_STATE_CAPACITY,
        )

        while (queue.isNotEmpty() && implementations.size < limit) {
            val current = queue.removeFirst()
            val edges = resolver.subtypeEdges(current, providerBudget)
            if (providerBudget.completion == EdgeDiscoveryCompletion.CANDIDATE_LIMIT_REACHED) {
                exhaustive = false
            }
            for (edge in edges) {
                val key = "${edge.symbol.fqName}|${edge.symbol.location.filePath}:${edge.symbol.location.startOffset}"
                if (!visited.add(key)) continue
                queue += edge.target
                if (ideaReadAccess.run { isConcreteType(edge.target) }) {
                    implementations += edge.symbol
                    if (implementations.size >= limit) {
                        exhaustive = false
                        break
                    }
                }
            }
        }

        if (queue.isNotEmpty()) exhaustive = false
        ImplementationsResult(
            declaration = declarationSymbol,
            implementations = implementations.sortedWith(
                compareBy({ it.fqName }, { it.location.filePath }, { it.location.startOffset }),
            ),
            exhaustive = exhaustive,
        )
    }
}

internal suspend fun KastPluginBackend.implementationRelationsOperation(
    query: KastImplementationsQuery,
): ImplementationRelationsResult = withContext(readDispatcher) {
    val continuationQuery = RelationshipContinuationStore.ImplementationQuery(
        selector = query.selector,
        limit = query.maxResults,
    )
    val initialAdmission = timedReadAction(
        telemetry,
        IdeaTelemetryScope.IMPLEMENTATIONS,
        "kast.idea.implementationRelations.admit",
    ) {
        completeRelationshipCoverageAdmission(query.selector, RelationshipRootKind.TYPE)
    }
    val generation = when (initialAdmission) {
        is CompleteRelationshipCoverageAdmission.Proven -> initialAdmission.generation
        is CompleteRelationshipCoverageAdmission.Limited ->
            return@withContext ImplementationRelationsResult.Limited(initialAdmission.evidence)
    }
    val handle = query.pageToken?.let(RelationTraversalHandle::parse)
    if (handle != null) {
        return@withContext timedReadAction(
            telemetry,
            IdeaTelemetryScope.IMPLEMENTATIONS,
            "kast.idea.implementationRelations.continue",
        ) {
            when (
                val commit = completeRelationshipCoverageAdmission(
                    query.selector,
                    RelationshipRootKind.TYPE,
                )
            ) {
                is CompleteRelationshipCoverageAdmission.Limited ->
                    ImplementationRelationsResult.Limited(commit.evidence)
                is CompleteRelationshipCoverageAdmission.Proven ->
                    relationshipContinuations.implementations(
                        continuationQuery,
                        handle,
                        null,
                        commit.generation,
                        commit.coverage,
                    )
            }
        }
    }
    val result = implementations(
        io.github.amichne.kast.api.contract.query.ImplementationsQuery(
            position = io.github.amichne.kast.api.contract.FilePosition(
                filePath = query.selector.declarationFile,
                offset = query.selector.declarationStartOffset,
            ),
            maxResults = KastPluginBackend.RELATIONSHIP_STATE_CAPACITY,
        ).parsed(),
    )
    if (!result.exhaustive) throw continuationConflict("candidateBudgetReached")
    val records = result.implementations.map { symbol ->
        ImplementationRelation(
            implementation = symbol.relationshipIdentity(),
            declarationLocation = symbol.location,
        )
    }
    if (records.size > KastPluginBackend.RELATIONSHIP_STATE_CAPACITY) {
        throw continuationConflict("traversalStateBudgetReached")
    }
    timedReadAction(
        telemetry,
        IdeaTelemetryScope.IMPLEMENTATIONS,
        "kast.idea.implementationRelations.commit",
    ) {
        when (
            val commit = completeRelationshipCoverageAdmission(
                query.selector,
                RelationshipRootKind.TYPE,
                requiredGeneration = generation,
                knownMinimumCount = records.size,
            )
        ) {
            is CompleteRelationshipCoverageAdmission.Limited ->
                ImplementationRelationsResult.Limited(commit.evidence, records)
            is CompleteRelationshipCoverageAdmission.Proven ->
                relationshipContinuations.implementations(
                    continuationQuery,
                    null,
                    records,
                    commit.generation,
                    commit.coverage,
                )
        }
    }
}
