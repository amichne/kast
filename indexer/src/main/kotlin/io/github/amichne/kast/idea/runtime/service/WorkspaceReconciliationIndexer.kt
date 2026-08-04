package io.github.amichne.kast.idea

import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.fields.GraphIndexingBatchSize
import io.github.amichne.kast.idea.diagnostics.KastSourceIndexSummary
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import java.nio.file.Path

internal class WorkspaceReconciliationIndexer(
    private val projectIndexer: IdeaProjectIndexer,
    private val workspaceRoot: Path,
    private val indexStore: SqliteSourceIndexStore,
    private val scopeCache: WorkspaceIndexingScopeCache,
    private val semanticGraphIndexer:
        (IndexedSourceIdentifiers, GraphIndexingBatchSize, IdeaIndexSemanticAdmission.ReconciliationToken) -> Unit,
    private val runProjectIndexing: ((KastConfig, (IndexedSourceIdentifiers) -> Unit) -> Unit)?,
) {
    fun run(
        liveConfig: KastConfig,
        candidate: WorkspaceReconciliationCandidate,
        reconciliationToken: IdeaIndexSemanticAdmission.ReconciliationToken,
    ): IndexingPassResult {
        var graphFailure: Throwable? = null
        val graph: (IndexedSourceIdentifiers) -> Unit = { scope ->
            runCatching {
                semanticGraphIndexer(scope, liveConfig.indexing.graph.batchSize, reconciliationToken)
            }.onFailure { error ->
                graphFailure = error
                LOG.warn("Kast semantic graph indexing pass failed", error)
            }
        }
        val indexedSources = runProjectIndexing?.let { indexProject ->
            indexProject(liveConfig, graph)
            scopeCache.resolve(
                workspaceRoot = workspaceRoot,
                config = liveConfig.indexing,
                candidates = indexStore.knownSourcePaths(),
            ).let { scope ->
                IndexedSourceIdentifiers(
                    paths = scope.includedPaths,
                    criticalPaths = scope.criticalPaths.toSet(),
                    unmatchedCriticalPatterns = scope.unmatchedCriticalPatterns,
                )
            }
        } ?: projectIndexer.indexProject(
            config = liveConfig,
            onSourceScopeReady = graph,
            candidate = checkNotNull(candidate.indexingCandidate) {
                "Production indexing requires a captured workspace candidate"
            },
            semanticContextIdentity = candidate.identity,
        )
        return IndexingPassResult(
            summary = indexStore.loadKastSourceIndexSummary(
                criticalPaths = indexedSources.criticalPaths,
                unmatchedCriticalPatterns = indexedSources.unmatchedCriticalPatterns,
            ),
            graphFailure = graphFailure,
        )
    }

    private companion object {
        val LOG = Logger.getInstance(WorkspaceReconciliationIndexer::class.java)
    }
}

internal data class IndexingPassResult(
    val summary: KastSourceIndexSummary,
    val graphFailure: Throwable?,
)
