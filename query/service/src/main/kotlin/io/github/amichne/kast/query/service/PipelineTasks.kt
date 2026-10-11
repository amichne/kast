package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryBindingRow
import io.github.amichne.kast.query.contract.QueryCheckpointStorageOwner
import io.github.amichne.kast.query.contract.QueryContainingDeclaration
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryRelationOmission
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QueryTextDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryWalkObservation
import io.github.amichne.kast.relation.contract.RelationContinuation
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.traversal.contract.TraversalContinuation

/** A detached depth-first ordered pipeline. Stage identity retains each distinct operator's own history. */
internal sealed interface PipelineTask : QueryCheckpointStorageOwner {
    override fun retainedBytes(graph: QueryImpactRetainedGraph): Long = retainedOwnerBytes(graph)

    sealed interface TraceTask : PipelineTask

    data class TraceMembers(
        val owner: QuerySymbol,
        val stage: ExactQueryStage.Trace,
        val page: io.github.amichne.kast.source.contract.SourceReadPage =
            io.github.amichne.kast.source.contract.SourceReadPage.First,
        val qualification: io.github.amichne.kast.source.contract.SourceReadQualification? = null,
    ) : TraceTask

    data class TraceMember(val member: QueryTraceMember, val stage: ExactQueryStage.Trace) : TraceTask

    data class ImpactExplore(val route: QueryImpactRoute) : PipelineTask

    data object ImpactFinalize : PipelineTask

    data class ValuePath(val value: io.github.amichne.kast.query.contract.QueryImpactPath) : PipelineTask

    data class Failure(val value: QueryItemFailure) : PipelineTask

    data class Omission(val value: QueryRelationOmission) : PipelineTask

    data class RelationObservation(val value: io.github.amichne.kast.query.contract.QueryRelationObservation) :
        PipelineTask

    data class WalkObservation(val value: QueryWalkObservation) : PipelineTask

    data class Occurrence(val value: io.github.amichne.kast.query.contract.QueryOccurrence) : PipelineTask

    data class ReferenceObservation(val value: io.github.amichne.kast.relation.contract.RelationReferenceOccurrence) :
        PipelineTask

    data class DiscoveryObservation(
        val value: io.github.amichne.kast.query.contract.QueryDiscoveryObservation,
        val origin: DiscoveryObservationOrigin = DiscoveryObservationOrigin.RETAINED_EVIDENCE,
    ) : PipelineTask

    data class WalkRecord(val value: QuerySymbol) : PipelineTask

    data class Discover(
        val syntax: QueryDiscoverySyntax,
        val next: ExactQueryStage,
        val remainder: io.github.amichne.kast.symbol.contract.SymbolDiscoveryRemainder? = null,
    ) : PipelineTask

    data class DiscoverLocation(val target: QueryContainingDeclaration, val next: ExactQueryStage) : PipelineTask

    data class DiscoverText(val syntax: QueryTextDiscoverySyntax, val next: ExactQueryStage) : PipelineTask

    data class Revalidate(val selector: SymbolSelector, val next: ExactQueryStage) : PipelineTask

    data class Feed(val stage: ExactQueryStage.Concat) : PipelineTask

    data class FlushSet(val stage: ExactQueryStage.Set) : PipelineTask

    data class FlushDistinct(val stage: ExactQueryStage.Distinct) : PipelineTask

    data class JoinEvidence(val stage: ExactQueryStage.Join) : PipelineTask

    data class Candidate(val value: SymbolDiscoverySelection, val stage: ExactQueryStage) : PipelineTask

    data class Symbol(val value: QuerySymbol, val stage: ExactQueryStage) : PipelineTask

    data class Binding(val value: QueryBindingRow, val stage: ExactQueryStage) : PipelineTask

    data class Related(val value: QuerySymbol, val stage: ExactQueryStage.Related, val cursor: RelationContinuation?) :
        PipelineTask

    data class Walk(val value: QuerySymbol, val stage: ExactQueryStage.Walk, val cursor: TraversalContinuation?) :
        PipelineTask

    data class Join(val value: QuerySymbol, val stage: ExactQueryStage.Join, val cursor: QueryJoinCursor) : PipelineTask
}

internal enum class DiscoveryObservationOrigin {
    SEQUENTIAL_EXECUTION,
    RETAINED_EVIDENCE,
}

internal sealed interface QueryJoinCursor {
    data object Build : QueryJoinCursor

    data class Inner(val nextMatch: Int) : QueryJoinCursor

    data class Semi(val nextMatch: Int, val accumulated: QuerySymbol) : QueryJoinCursor

    data object Anti : QueryJoinCursor
}

internal fun PipelineTask.needsWork(): Boolean =
    when (this) {
        is PipelineTask.TraceMembers,
        is PipelineTask.TraceMember,
        is PipelineTask.Discover,
        is PipelineTask.DiscoverText,
        is PipelineTask.DiscoverLocation,
        is PipelineTask.Revalidate,
        is PipelineTask.Related,
        is PipelineTask.Walk,
        is PipelineTask.Join -> true
        is PipelineTask.Binding -> false
        else -> false
    }
