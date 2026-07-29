package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.idea.diagnostics.KastBackendUiState
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import java.nio.file.Path

internal enum class KastToolWindowContent(
    val title: String,
) {
    EXPLORE("Explore"),
    ACTIVITY("Activity"),
}

data class KastSourceTarget(
    val filePath: Path,
    val offset: Int,
) {
    init {
        require(filePath.isAbsolute) { "Kast source targets must be absolute" }
        require(offset >= 0) { "Kast source target offsets must be non-negative" }
    }
}

data class KastExplorerSearchItem(
    val declaration: DeclarationRow,
) {
    val displayName: String = declaration.fqName.substringAfterLast('.')
    val ownerName: String = declaration.fqName.substringBeforeLast('.', "")
    val navigationTarget: KastSourceTarget? = declaration.declarationOffset?.let { offset ->
        KastSourceTarget(Path.of(declaration.filePath).toAbsolutePath().normalize(), offset)
    }
}

enum class KastExplorerEvidenceLayer(
    val title: String,
) {
    INCOMING("Incoming references"),
    SEMANTIC_GRAPH("Semantic graph"),
}

data class KastExplorerRelation(
    val layer: KastExplorerEvidenceLayer,
    val title: NonBlankString,
    val detail: NonBlankString?,
    val navigationTarget: KastSourceTarget?,
)

data class KastExplorerSection(
    val layer: KastExplorerEvidenceLayer,
    val relations: List<KastExplorerRelation>,
)

data class KastExplorerInspection(
    val selected: KastExplorerSearchItem,
    val relations: List<KastExplorerRelation>,
) {
    val sections: List<KastExplorerSection> = KastExplorerEvidenceLayer.entries.map { layer ->
        KastExplorerSection(layer, relations.filter { relation -> relation.layer == layer })
    }
}

data class KastExplorerOverview(
    val graphGeneration: SourceIndexGeneration,
    val graphFileCount: NonNegativeInt,
)

sealed interface KastExplorerRequest {
    data object Overview : KastExplorerRequest

    data class Search(
        val pattern: NonBlankString,
        val maxResults: PositiveInt = PositiveInt(DEFAULT_SEARCH_LIMIT),
    ) : KastExplorerRequest {
        companion object {
            const val DEFAULT_SEARCH_LIMIT: Int = 75

            fun parse(rawPattern: String): Search? =
                rawPattern.trim().takeIf(String::isNotEmpty)?.let(::NonBlankString)?.let(::Search)
        }
    }

    data class Inspect(
        val item: KastExplorerSearchItem,
    ) : KastExplorerRequest
}

sealed interface KastExplorerResult {
    data class Overview(
        val value: KastExplorerOverview,
    ) : KastExplorerResult

    data class SearchResults(
        val items: List<KastExplorerSearchItem>,
    ) : KastExplorerResult

    data class Inspection(
        val value: KastExplorerInspection,
    ) : KastExplorerResult

    data class Problem(
        val message: NonBlankString,
    ) : KastExplorerResult
}

internal class KastExplorerModel {
    var overview: KastExplorerOverview? = null
        private set
    var searchItems: List<KastExplorerSearchItem> = emptyList()
        private set
    var inspection: KastExplorerInspection? = null
        private set
    var problem: NonBlankString? = null
        private set

    fun accept(result: KastExplorerResult) {
        problem = null
        when (result) {
            is KastExplorerResult.Overview -> overview = result.value
            is KastExplorerResult.SearchResults -> {
                searchItems = result.items
                inspection = null
            }
            is KastExplorerResult.Inspection -> inspection = result.value
            is KastExplorerResult.Problem -> problem = result.message
        }
    }
}

internal fun shouldAcceptExplorerResult(
    request: KastExplorerRequest,
    resultSequence: Long,
    currentSequence: Long,
): Boolean = request is KastExplorerRequest.Overview || resultSequence == currentSequence

internal fun shouldRefreshExplorerOverview(
    previousState: KastBackendUiState,
    currentState: KastBackendUiState,
): Boolean = previousState != KastBackendUiState.READY && currentState == KastBackendUiState.READY

internal fun nextExplorerRequestSequence(
    request: KastExplorerRequest,
    currentSequence: Long,
): Long = if (request is KastExplorerRequest.Overview) currentSequence else currentSequence + 1
