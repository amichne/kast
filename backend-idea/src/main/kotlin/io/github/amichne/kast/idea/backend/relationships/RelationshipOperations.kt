@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.relationships

import io.github.amichne.kast.idea.backend.KastPluginBackend

import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiElement
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.api.contract.result.CallHierarchyResult
import io.github.amichne.kast.api.contract.result.CallRelationsResult
import io.github.amichne.kast.api.contract.result.ImplementationsResult
import io.github.amichne.kast.api.contract.result.ImplementationRelation
import io.github.amichne.kast.api.contract.result.ImplementationRelationsResult
import io.github.amichne.kast.api.contract.result.HierarchyRelationsResult
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import io.github.amichne.kast.api.contract.result.TypeHierarchyRelation
import io.github.amichne.kast.api.contract.result.RelationTraversalHandle
import io.github.amichne.kast.api.contract.skill.KastCallersQuery
import io.github.amichne.kast.api.contract.skill.KastHierarchyQuery
import io.github.amichne.kast.api.contract.skill.KastImplementationsQuery
import io.github.amichne.kast.shared.analysis.resolveTarget
import io.github.amichne.kast.shared.analysis.typeHierarchyDeclaration
import io.github.amichne.kast.shared.hierarchy.CallHierarchyEngine
import io.github.amichne.kast.shared.hierarchy.TypeHierarchyBudget
import io.github.amichne.kast.shared.hierarchy.TypeHierarchyEngine
import io.github.amichne.kast.shared.hierarchy.TraversalBudget
import kotlinx.coroutines.withContext
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.edit.*
import io.github.amichne.kast.idea.backend.references.*
import io.github.amichne.kast.idea.backend.relationships.*
import io.github.amichne.kast.idea.backend.diagnostics.*
import io.github.amichne.kast.idea.backend.mutation.*
import io.github.amichne.kast.idea.backend.workspace.*
import io.github.amichne.kast.idea.backend.*

internal suspend fun KastPluginBackend.callHierarchyOperation(query: ParsedCallHierarchyQuery): CallHierarchyResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.CALL_HIERARCHY, "kast.idea.callHierarchy") {
        // Resolve the root target under a short read lock; the recursive
        // traversal acquires per-level read locks inside the edge resolver
        // so the IDE write lock is not starved for the full duration.
        val rootTarget = timedReadAction(telemetry, IdeaTelemetryScope.CALL_HIERARCHY, "kast.idea.callHierarchy.resolveTarget") {
            val file = findKtFile(query.position.filePath.value)
            resolveTarget(file, query.position.offset.value)
        }

        val budget = TraversalBudget(
            maxTotalCalls = query.maxTotalCalls.value,
            maxChildrenPerNode = query.maxChildrenPerNode.value,
            timeoutMillis = query.timeoutMillis?.value ?: limits.requestTimeoutMillis,
        )
        val resolver = IdeaCallEdgeResolver(
            project = project,
            workspaceIdentity = sharedWorkspaceIdentity,
        )
        val engine = CallHierarchyEngine(edgeResolver = resolver, readAccess = ideaReadAccess)
        val root = engine.buildNode(
            target = rootTarget,
            parentCallSite = null,
            direction = query.direction,
            depthRemaining = query.depth.value,
            pathKeys = emptySet(),
            budget = budget,
            currentDepth = 0,
        )

        CallHierarchyResult(
            root = root,
            stats = budget.toStats(),
        )
        }
    }

internal suspend fun KastPluginBackend.callRelationsOperation(query: KastCallersQuery): CallRelationsResult =
        withContext(readDispatcher) {
            val continuationQuery = RelationshipContinuationStore.CallQuery(
                selector = query.selector,
                direction = query.direction,
                depth = query.depth,
                limit = query.maxResults,
            )
            val initialAdmission = timedReadAction(
                telemetry,
                IdeaTelemetryScope.CALL_HIERARCHY,
                "kast.idea.callRelations.admit",
            ) {
                completeRelationshipCoverageAdmission(query.selector, RelationshipRootKind.CALLABLE)
            }
            val generation = when (initialAdmission) {
                is CompleteRelationshipCoverageAdmission.Proven -> initialAdmission.generation
                is CompleteRelationshipCoverageAdmission.Limited ->
                    return@withContext CallRelationsResult.Limited(initialAdmission.evidence)
            }
            val handle = query.pageToken?.let(RelationTraversalHandle::parse)
            if (handle != null) {
                return@withContext timedReadAction(
                    telemetry,
                    IdeaTelemetryScope.CALL_HIERARCHY,
                    "kast.idea.callRelations.continue",
                ) {
                    when (
                        val commit = completeRelationshipCoverageAdmission(
                            query.selector,
                            RelationshipRootKind.CALLABLE,
                        )
                    ) {
                        is CompleteRelationshipCoverageAdmission.Limited ->
                            CallRelationsResult.Limited(commit.evidence)
                        is CompleteRelationshipCoverageAdmission.Proven ->
                            relationshipContinuations.calls(
                                continuationQuery,
                                handle,
                                null,
                                commit.generation,
                                commit.coverage,
                            )
                    }
                }
            }
            val direction = when (query.direction) {
                io.github.amichne.kast.api.contract.skill.WrapperCallDirection.INCOMING ->
                    io.github.amichne.kast.api.contract.CallDirection.INCOMING
                io.github.amichne.kast.api.contract.skill.WrapperCallDirection.OUTGOING ->
                    io.github.amichne.kast.api.contract.CallDirection.OUTGOING
            }
            val result = callHierarchy(
                io.github.amichne.kast.api.contract.query.CallHierarchyQuery(
                    position = io.github.amichne.kast.api.contract.FilePosition(
                        filePath = query.selector.declarationFile,
                        offset = query.selector.declarationStartOffset,
                    ),
                    direction = direction,
                    depth = query.depth,
                    maxTotalCalls = KastPluginBackend.RELATIONSHIP_STATE_CAPACITY,
                    maxChildrenPerNode = KastPluginBackend.RELATIONSHIP_STATE_CAPACITY,
                    timeoutMillis = limits.requestTimeoutMillis,
                ).parsed(),
            )
            if (result.stats.timeoutReached) throw continuationConflict("timeout")
            if (result.stats.truncatedNodes > 0 ||
                result.stats.maxTotalCallsReached ||
                result.stats.maxChildrenPerNodeReached
            ) {
                throw continuationConflict("candidateBudgetReached")
            }
            val records = flattenCallRelations(result.root, direction)
            if (records.size > KastPluginBackend.RELATIONSHIP_STATE_CAPACITY) {
                throw continuationConflict("traversalStateBudgetReached")
            }
            timedReadAction(
                telemetry,
                IdeaTelemetryScope.CALL_HIERARCHY,
                "kast.idea.callRelations.commit",
            ) {
                when (
                    val commit = completeRelationshipCoverageAdmission(
                        query.selector,
                        RelationshipRootKind.CALLABLE,
                        requiredGeneration = generation,
                    )
                ) {
                    is CompleteRelationshipCoverageAdmission.Limited ->
                        CallRelationsResult.Limited(commit.evidence)
                    is CompleteRelationshipCoverageAdmission.Proven ->
                        relationshipContinuations.calls(
                            continuationQuery,
                            null,
                            records,
                            commit.generation,
                            commit.coverage,
                        )
                }
            }
        }

internal suspend fun KastPluginBackend.typeHierarchyOperation(query: ParsedTypeHierarchyQuery): TypeHierarchyResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.TYPE_HIERARCHY, "kast.idea.typeHierarchy") {
        val rootTarget = readAction {
            val file = findKtFile(query.position.filePath.value)
            val resolved = resolveTarget(file, query.position.offset.value)
            resolved.typeHierarchyDeclaration() ?: resolved
        }
        val resolver = IdeaTypeEdgeResolver(project = project)
        val engine = TypeHierarchyEngine(edgeResolver = resolver, readAccess = ideaReadAccess)
        val budget = TypeHierarchyBudget(maxResults = query.maxResults.value)
        val root = engine.buildNode(
            target = rootTarget,
            direction = query.direction,
            depthRemaining = query.depth.value,
            pathKeys = emptySet(),
            budget = budget,
            currentDepth = 0,
        )
        TypeHierarchyResult(root = root, stats = budget.toStats())
        }
    }

internal suspend fun KastPluginBackend.hierarchyRelationsOperation(query: KastHierarchyQuery): HierarchyRelationsResult =
        withContext(readDispatcher) {
            val continuationQuery = RelationshipContinuationStore.HierarchyQuery(
                selector = query.selector,
                direction = query.direction,
                depth = query.depth,
                limit = query.maxResults,
            )
            val initialAdmission = timedReadAction(
                telemetry,
                IdeaTelemetryScope.TYPE_HIERARCHY,
                "kast.idea.hierarchyRelations.admit",
            ) {
                completeRelationshipCoverageAdmission(query.selector, RelationshipRootKind.TYPE)
            }
            val generation = when (initialAdmission) {
                is CompleteRelationshipCoverageAdmission.Proven -> initialAdmission.generation
                is CompleteRelationshipCoverageAdmission.Limited ->
                    return@withContext HierarchyRelationsResult.Limited(initialAdmission.evidence)
            }
            val handle = query.pageToken?.let(RelationTraversalHandle::parse)
            if (handle != null) {
                return@withContext timedReadAction(
                    telemetry,
                    IdeaTelemetryScope.TYPE_HIERARCHY,
                    "kast.idea.hierarchyRelations.continue",
                ) {
                    when (
                        val commit = completeRelationshipCoverageAdmission(
                            query.selector,
                            RelationshipRootKind.TYPE,
                        )
                    ) {
                        is CompleteRelationshipCoverageAdmission.Limited ->
                            HierarchyRelationsResult.Limited(commit.evidence)
                        is CompleteRelationshipCoverageAdmission.Proven ->
                            relationshipContinuations.hierarchy(
                                continuationQuery,
                                handle,
                                null,
                                commit.generation,
                                commit.coverage,
                            )
                    }
                }
            }
            val directions = when (query.direction) {
                io.github.amichne.kast.api.contract.TypeHierarchyDirection.SUPERTYPES ->
                    listOf(io.github.amichne.kast.api.contract.TypeHierarchyDirection.SUPERTYPES)
                io.github.amichne.kast.api.contract.TypeHierarchyDirection.SUBTYPES ->
                    listOf(io.github.amichne.kast.api.contract.TypeHierarchyDirection.SUBTYPES)
                io.github.amichne.kast.api.contract.TypeHierarchyDirection.BOTH -> listOf(
                    io.github.amichne.kast.api.contract.TypeHierarchyDirection.SUPERTYPES,
                    io.github.amichne.kast.api.contract.TypeHierarchyDirection.SUBTYPES,
                )
            }
            val records = directions.flatMap { direction ->
                val result = typeHierarchy(
                    io.github.amichne.kast.api.contract.query.TypeHierarchyQuery(
                        position = io.github.amichne.kast.api.contract.FilePosition(
                            filePath = query.selector.declarationFile,
                            offset = query.selector.declarationStartOffset,
                        ),
                        direction = direction,
                        depth = query.depth,
                        maxResults = KastPluginBackend.RELATIONSHIP_STATE_CAPACITY,
                    ).parsed(),
                )
                if (result.stats.truncated) throw continuationConflict("candidateBudgetReached")
                flattenHierarchyRelations(result.root, direction)
            }.sortedWith(
                compareBy<TypeHierarchyRelation>(
                    TypeHierarchyRelation::depth,
                    { relation -> relation.relatedSymbol.fqName },
                    { relation -> relation.relatedSymbol.kind.name },
                    { relation -> relation.relatedSymbol.declarationFile.value },
                    { relation -> relation.relatedSymbol.declarationStartOffset.value },
                    { relation -> relation.relation.name },
                ),
            )
            if (records.size > KastPluginBackend.RELATIONSHIP_STATE_CAPACITY) {
                throw continuationConflict("traversalStateBudgetReached")
            }
            timedReadAction(
                telemetry,
                IdeaTelemetryScope.TYPE_HIERARCHY,
                "kast.idea.hierarchyRelations.commit",
            ) {
                when (
                    val commit = completeRelationshipCoverageAdmission(
                        query.selector,
                        RelationshipRootKind.TYPE,
                        requiredGeneration = generation,
                    )
                ) {
                    is CompleteRelationshipCoverageAdmission.Limited ->
                        HierarchyRelationsResult.Limited(commit.evidence)
                    is CompleteRelationshipCoverageAdmission.Proven ->
                        relationshipContinuations.hierarchy(
                            continuationQuery,
                            null,
                            records,
                            commit.generation,
                            commit.coverage,
                        )
                }
            }
        }

internal suspend fun KastPluginBackend.implementationsOperation(query: ParsedImplementationsQuery): ImplementationsResult = withContext(readDispatcher) {
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

        while (queue.isNotEmpty() && implementations.size < limit) {
            val current = queue.removeFirst()
            val edges = resolver.subtypeEdges(current)
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
                )
            ) {
                is CompleteRelationshipCoverageAdmission.Limited ->
                    ImplementationRelationsResult.Limited(commit.evidence)
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
