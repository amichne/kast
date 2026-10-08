package io.github.amichne.kast.change.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Persisted replay retains the semantic extent independently from execution grants. */
@Serializable
internal sealed interface LiveTraversalExtentDocument {
    @Serializable @SerialName("EXHAUSTIVE") data object Exhaustive : LiveTraversalExtentDocument

    @Serializable
    @SerialName("THROUGH_DEPTH")
    data class ThroughDepth(val maximumDepth: Int) : LiveTraversalExtentDocument
}

/** Canonical v2 remains byte-exact when every traversal question retains its original explicit bound. */
internal sealed interface LiveVerificationScopeRepresentation {
    data class LegacyDepth(val document: LegacyLiveVerificationScopeDocument) : LiveVerificationScopeRepresentation

    data class Extent(val document: LiveVerificationScopeDocument) : LiveVerificationScopeRepresentation
}

@Serializable
internal data class LegacyLiveVerificationScopeDocument(
    val relations: List<LiveRelationReplayDocument>,
    val traversals: List<LegacyLiveTraversalReplayDocument>,
    val diagnostics: List<List<String>>,
) {
    fun migrated(): LiveVerificationScopeDocument =
        LiveVerificationScopeDocument(
            relations,
            traversals.map { replay ->
                LiveTraversalReplayDocument(
                    replay.meaning,
                    replay.records,
                    replay.returnedBytes,
                    replay.workUnits,
                    replay.elapsedTime,
                    LiveTraversalExtentDocument.ThroughDepth(replay.depth),
                    replay.frontier,
                    replay.oneHop,
                    replay.expansion,
                )
            },
            diagnostics,
        )
}

@Serializable
internal data class LegacyLiveTraversalReplayDocument(
    val meaning: String,
    val records: Int,
    val returnedBytes: Long,
    val workUnits: Long,
    val elapsedTime: Long,
    val depth: Int,
    val frontier: Int,
    val oneHop: LiveRelationBudgetDocument,
    val expansion: LiveRelationBoundaryDocument,
)
