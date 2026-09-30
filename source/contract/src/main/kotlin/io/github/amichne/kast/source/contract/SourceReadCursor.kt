package io.github.amichne.kast.source.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.SymbolSearchScope

/** Request-local access to detached cursors owned by the final source-page publication. */
interface SourceReadContinuationPort {
    fun admit(
        context: SourceReadContext,
        request: SourceReadRequest,
    ): Refinement<SourceReadEntityCursor, SourceReadRejection>

    fun issue(proof: SourceReadCursorProof): Refinement<SourceReadContinuation, SourceReadCursorRetentionFailure>

    /** One-shot source projections may establish entities but grant no retained native cursor capacity. */
    data object Cursorless : SourceReadContinuationPort {
        override fun admit(
            context: SourceReadContext,
            request: SourceReadRequest,
        ): Refinement<SourceReadEntityCursor, SourceReadRejection> =
            when (request.page) {
                SourceReadPage.First -> Refinement.Refined(SourceReadEntityCursor.First)
                is SourceReadPage.Continue -> Refinement.Rejected(SourceReadRejection.CONTINUATION_UNAVAILABLE)
            }

        override fun issue(
            proof: SourceReadCursorProof
        ): Refinement<SourceReadContinuation, SourceReadCursorRetentionFailure> =
            Refinement.Rejected(SourceReadCursorRetentionFailure.CAPACITY_EXCEEDED)
    }
}

enum class SourceReadCursorFailure {
    NON_ADVANCING,
    AUTHORITY_MISMATCH,
    REQUEST_MISMATCH,
    ENTITY_STREAM_ABSENT,
    TRAVERSAL_MISMATCH,
}

enum class SourceReadCursorRetentionFailure {
    CAPACITY_EXCEEDED,
    OWNER_UNAVAILABLE,
    INVALID_STATE,
}

/** A resumed position cannot exist without the captured region and its request binding. */
sealed interface SourceReadEntityCursor {
    val startOrdinal: Int

    data object First : SourceReadEntityCursor {
        override val startOrdinal: Int = 0
    }

    class Continued internal constructor(val proof: SourceReadCursorProof) : SourceReadEntityCursor {
        override val startOrdinal: Int
            get() = proof.nextOrdinal.value
    }
}

@JvmInline
value class SourceEntityOrdinal private constructor(val value: Int) {
    companion object {
        internal fun advancing(
            previous: SourceReadEntityCursor,
            next: Int,
        ): Refinement<SourceEntityOrdinal, SourceReadCursorFailure> =
            if (next < previous.startOrdinal) Refinement.Rejected(SourceReadCursorFailure.NON_ADVANCING)
            else Refinement.Refined(SourceEntityOrdinal(next))
    }
}

/** Detached source proof; neither PSI nor a compiler session can enter this representation. */
class SourceReadCursorProof
private constructor(
    private val request: SourceCursorRequest,
    val region: SourceSelector,
    val nextOrdinal: SourceEntityOrdinal,
    val traversal: SourceEntityTraversalState,
) {
    val snapshot: SourceSnapshot
        get() = region.snapshot

    /** Conservative accounting of every retained authority, selector hierarchy and request field. */
    val retainedBytes: Long = 4_096L + request.retainedBytes + region.detachedRetentionBytes() + traversal.retainedBytes

    fun admit(
        context: SourceReadContext,
        request: SourceReadRequest,
    ): Refinement<SourceReadEntityCursor, SourceReadRejection> =
        when {
            snapshot.context != context -> Refinement.Rejected(SourceReadRejection.SOURCE_SNAPSHOT_MISMATCH)
            !this.request.matches(request) -> Refinement.Rejected(SourceReadRejection.CONTINUATION_REQUEST_MISMATCH)
            else -> Refinement.Refined(SourceReadEntityCursor.Continued(this))
        }

    fun matches(selected: SourceSelector): Boolean =
        selected.snapshot == snapshot && selected.fingerprint == region.fingerprint

    fun samePosition(other: SourceReadCursorProof): Boolean =
        nextOrdinal == other.nextOrdinal &&
            matches(other.region) &&
            request.sameRequest(other.request) &&
            traversal.samePosition(other.traversal)

    companion object {
        fun create(
            request: SourceReadRequest,
            region: SourceSelector,
            previous: SourceReadEntityCursor,
            nextOrdinal: Int,
            traversal: SourceEntityTraversalState,
        ): Refinement<SourceReadCursorProof, SourceReadCursorFailure> {
            if (request.entities == EntitySelection.None) {
                return Refinement.Rejected(SourceReadCursorFailure.ENTITY_STREAM_ABSENT)
            }
            when (val admitted = admitAuthority(request, region, previous)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            if (previous is SourceReadEntityCursor.Continued && !previous.proof.request.matches(request)) {
                return Refinement.Rejected(SourceReadCursorFailure.REQUEST_MISMATCH)
            }
            when (val admitted = admitTraversal(region, previous, traversal)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            return when (val ordinal = SourceEntityOrdinal.advancing(previous, nextOrdinal)) {
                is Refinement.Rejected -> ordinal
                is Refinement.Refined ->
                    Refinement.Refined(
                        SourceReadCursorProof(SourceCursorRequest.from(request), region, ordinal.value, traversal)
                    )
            }
        }

        private fun admitAuthority(
            request: SourceReadRequest,
            region: SourceSelector,
            previous: SourceReadEntityCursor,
        ): Refinement<Unit, SourceReadCursorFailure> {
            val authority =
                when (val anchor = request.anchor) {
                    is SourceReadAnchor.Candidate -> anchor.selector.lease
                    is SourceReadAnchor.Symbol -> anchor.selector.lease
                    is SourceReadAnchor.Source -> anchor.selector.snapshot.lease
                }
            val contextMatches =
                request.anchor !is SourceReadAnchor.Source ||
                    request.anchor.selector.snapshot.context == region.snapshot.context
            val previousMatches = previous !is SourceReadEntityCursor.Continued || previous.proof.matches(region)
            if (authority != region.snapshot.lease || !contextMatches || !previousMatches) {
                return Refinement.Rejected(SourceReadCursorFailure.AUTHORITY_MISMATCH)
            }
            return Refinement.Refined(Unit)
        }

        private fun admitTraversal(
            region: SourceSelector,
            previous: SourceReadEntityCursor,
            traversal: SourceEntityTraversalState,
        ): Refinement<Unit, SourceReadCursorFailure> {
            if (traversal.region.snapshot != region.snapshot || traversal.region.fingerprint != region.fingerprint) {
                return Refinement.Rejected(SourceReadCursorFailure.TRAVERSAL_MISMATCH)
            }
            if (
                previous is SourceReadEntityCursor.Continued &&
                    (traversal.revision <= previous.proof.traversal.revision ||
                        !traversal.limitations.containsAll(previous.proof.traversal.limitations))
            ) {
                return Refinement.Rejected(SourceReadCursorFailure.NON_ADVANCING)
            }
            return Refinement.Refined(Unit)
        }
    }
}

/** Presentation grants and predecessor tokens do not change the semantic stream. */
private class SourceCursorRequest
private constructor(
    val anchor: SourceReadAnchor,
    val region: RegionSelection,
    val entities: EntitySelection,
    val text: TextProjection,
    val output: SourceReadOutputIdentity,
) {
    val retainedBytes: Long = anchor.detachedRetentionBytes() + 1_024L

    fun matches(other: SourceReadRequest): Boolean = sameRequest(from(other))

    fun sameRequest(other: SourceCursorRequest): Boolean =
        anchor.sameAnchor(other.anchor) &&
            region == other.region &&
            entities.sameSelection(other.entities) &&
            text == other.text &&
            output == other.output

    companion object {
        fun from(request: SourceReadRequest): SourceCursorRequest =
            SourceCursorRequest(
                request.anchor,
                request.region,
                request.entities,
                request.text,
                request.outputIdentity,
            )
    }
}

private fun SourceReadAnchor.sameAnchor(other: SourceReadAnchor): Boolean =
    when (this) {
        is SourceReadAnchor.Candidate -> other is SourceReadAnchor.Candidate && selector.sameCandidate(other.selector)
        is SourceReadAnchor.Symbol ->
            other is SourceReadAnchor.Symbol &&
                selector.fingerprint == other.selector.fingerprint &&
                selector.lease == other.selector.lease
        is SourceReadAnchor.Source ->
            other is SourceReadAnchor.Source &&
                selector.fingerprint == other.selector.fingerprint &&
                selector.snapshot == other.selector.snapshot
    }

private fun CandidateSelector.sameCandidate(other: CandidateSelector): Boolean =
    lease == other.lease &&
        SymbolSearchScope.snapshot(scope) == SymbolSearchScope.snapshot(other.scope) &&
        constraints == other.constraints &&
        when (this) {
            is CandidateSelector.Declaration ->
                other is CandidateSelector.Declaration && selection.candidate == other.selection.candidate
            is CandidateSelector.Range ->
                other is CandidateSelector.Range &&
                    file == other.file &&
                    startInclusive == other.startInclusive &&
                    endExclusive == other.endExclusive
        }

private fun EntitySelection.sameSelection(other: EntitySelection): Boolean =
    when (this) {
        EntitySelection.None -> other == EntitySelection.None
        is EntitySelection.Matching ->
            other is EntitySelection.Matching && containment == other.containment && filters == other.filters
    }
