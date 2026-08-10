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
    private val semanticGraphIndexer: SemanticGraphIndexingTransition,
    private val runProjectIndexing: ((KastConfig, (IndexedSourceIdentifiers) -> Unit) -> Unit)?,
) {
    fun run(
        liveConfig: KastConfig,
        candidate: WorkspaceReconciliationCandidate,
        reconciliationToken: IdeaIndexSemanticAdmission.ReconciliationToken,
    ): IndexingPassResult {
        var graphOutcome: GraphLaneOutcome = GraphLaneOutcome.Committed
        val graph: (IndexedSourceIdentifiers) -> Unit = { scope ->
            val input = GraphIndexingTransitionInput.admit(
                sourceIdentifiers = scope,
                batchSize = liveConfig.indexing.graph.batchSize,
                reconciliationToken = reconciliationToken,
            )
            graphOutcome = runCatching { semanticGraphIndexer.advance(input) }
                .getOrElse { error ->
                    LOG.warn("Kast semantic graph indexing pass failed", error)
                    GraphLaneOutcome.Blocked(GraphLaneBlocker.INDEXING_FAILED)
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
            graphOutcome = graphOutcome,
        )
    }

    private companion object {
        val LOG = Logger.getInstance(WorkspaceReconciliationIndexer::class.java)
    }
}

internal class GraphIndexingTransitionInput private constructor(
    val sourceIdentifiers: IndexedSourceIdentifiers,
    val batchSize: GraphIndexingBatchSize,
    val reconciliationToken: IdeaIndexSemanticAdmission.ReconciliationToken,
) {
    companion object {
        /**
         * Proof transition: `(IndexedSourceIdentifiers, GraphIndexingBatchSize, ReconciliationToken) -> GraphIndexingTransitionInput`.
         *
         * Establishes that graph work is bound to the source identifiers published by this indexing
         * pass, its admitted batch size, and the same reconciliation token. Raw field extraction is
         * permitted only at the semantic-graph effect boundary.
         */
        fun admit(
            sourceIdentifiers: IndexedSourceIdentifiers,
            batchSize: GraphIndexingBatchSize,
            reconciliationToken: IdeaIndexSemanticAdmission.ReconciliationToken,
        ): GraphIndexingTransitionInput = GraphIndexingTransitionInput(
            sourceIdentifiers = sourceIdentifiers,
            batchSize = batchSize,
            reconciliationToken = reconciliationToken,
        )
    }
}

internal fun interface SemanticGraphIndexingTransition {
    /**
     * Proof transition: `GraphIndexingTransitionInput -> GraphLaneOutcome`.
     *
     * Establishes either committed graph evidence for the input's source lane or the closed
     * `GraphLaneBlocker` that prevents graph publication. Raw graph backend calls are permitted only
     * inside an implementation of this transition.
     */
    fun advance(input: GraphIndexingTransitionInput): GraphLaneOutcome

    companion object {
        fun disabled(): SemanticGraphIndexingTransition = SemanticGraphIndexingTransition {
            GraphLaneOutcome.Committed
        }
    }
}

internal data class IndexingPassResult(
    val summary: KastSourceIndexSummary,
    val graphOutcome: GraphLaneOutcome,
)

internal sealed interface GraphLaneOutcome {
    data object Committed : GraphLaneOutcome

    data class Blocked(val blocker: GraphLaneBlocker) : GraphLaneOutcome
}

internal enum class GraphLaneBlocker {
    INDEXING_FAILED,
}
