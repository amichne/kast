package io.github.amichne.kast.idea

import io.github.amichne.kast.api.continuation.ContinuationAccessFailure
import io.github.amichne.kast.api.continuation.ContinuationConsumeResult
import io.github.amichne.kast.api.continuation.ContinuationIssueResult
import io.github.amichne.kast.api.continuation.ContinuationOwnedState
import io.github.amichne.kast.api.continuation.ContinuationProjection
import io.github.amichne.kast.api.continuation.ContinuationStateDisposer
import io.github.amichne.kast.api.continuation.ContinuationStateTransition
import io.github.amichne.kast.api.continuation.ContinuationTokenIssuer
import io.github.amichne.kast.api.continuation.ContinuationTransition
import io.github.amichne.kast.api.continuation.ServerHeldContinuationStore
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.result.CallRelation
import io.github.amichne.kast.api.contract.result.CallRelationsResult
import io.github.amichne.kast.api.contract.result.HierarchyRelationsResult
import io.github.amichne.kast.api.contract.result.ImplementationRelation
import io.github.amichne.kast.api.contract.result.ImplementationRelationsResult
import io.github.amichne.kast.api.contract.result.RelationTraversalFamily
import io.github.amichne.kast.api.contract.result.RelationTraversalHandle
import io.github.amichne.kast.api.contract.result.RelationTraversalPageInfo
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.TypeHierarchyRelation
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.api.protocol.ConflictException
import java.util.UUID

internal class RelationshipContinuationStore(
    limits: ServerLimits,
) : AutoCloseable {
    private val callerStore = TypedRelationshipStore<CallQuery, CallRelation>(
        family = RelationTraversalFamily.CALLERS,
        limits = limits,
    )
    private val calleeStore = TypedRelationshipStore<CallQuery, CallRelation>(
        family = RelationTraversalFamily.CALLEES,
        limits = limits,
    )
    private val implementationStore =
        TypedRelationshipStore<ImplementationQuery, ImplementationRelation>(
            family = RelationTraversalFamily.IMPLEMENTATIONS,
            limits = limits,
        )
    private val hierarchyStore = TypedRelationshipStore<HierarchyQuery, TypeHierarchyRelation>(
        family = RelationTraversalFamily.HIERARCHY,
        limits = limits,
    )

    data class CallQuery(
        val selector: KastExactSymbolSelector,
        val direction: io.github.amichne.kast.api.contract.skill.WrapperCallDirection,
        val depth: Int,
        val limit: Int,
    ) {
        init {
            require(depth >= 0) { "Relationship query depth must be non-negative" }
            require(limit > 0) { "Relationship query limit must be positive" }
        }
    }

    data class ImplementationQuery(
        val selector: KastExactSymbolSelector,
        val limit: Int,
    ) {
        init {
            require(limit > 0) { "Relationship query limit must be positive" }
        }
    }

    data class HierarchyQuery(
        val selector: KastExactSymbolSelector,
        val direction: io.github.amichne.kast.api.contract.TypeHierarchyDirection,
        val depth: Int,
        val limit: Int,
    ) {
        init {
            require(depth >= 0) { "Relationship query depth must be non-negative" }
            require(limit > 0) { "Relationship query limit must be positive" }
        }
    }

    fun calls(
        query: CallQuery,
        handle: RelationTraversalHandle?,
        initialRecords: List<CallRelation>?,
        generation: Long,
    ): CallRelationsResult {
        val store = when (query.direction) {
            io.github.amichne.kast.api.contract.skill.WrapperCallDirection.INCOMING -> callerStore
            io.github.amichne.kast.api.contract.skill.WrapperCallDirection.OUTGOING -> calleeStore
        }
        val page = store.page(query, query.limit, handle, initialRecords, generation)
        return CallRelationsResult(page.records, page.pageInfo)
    }

    fun implementations(
        query: ImplementationQuery,
        handle: RelationTraversalHandle?,
        initialRecords: List<ImplementationRelation>?,
        generation: Long,
    ): ImplementationRelationsResult {
        val page = implementationStore.page(query, query.limit, handle, initialRecords, generation)
        return ImplementationRelationsResult(page.records, page.pageInfo)
    }

    fun hierarchy(
        query: HierarchyQuery,
        handle: RelationTraversalHandle?,
        initialRecords: List<TypeHierarchyRelation>?,
        generation: Long,
    ): HierarchyRelationsResult {
        val page = hierarchyStore.page(query, query.limit, handle, initialRecords, generation)
        return HierarchyRelationsResult(page.records, page.pageInfo)
    }

    override fun close() {
        val failures = mutableListOf<Throwable>()
        callerStore.closeRecording(failures)
        calleeStore.closeRecording(failures)
        implementationStore.closeRecording(failures)
        hierarchyStore.closeRecording(failures)
        failures.firstOrNull()?.let { primary ->
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
    }

    private fun AutoCloseable.closeRecording(failures: MutableList<Throwable>) {
        try {
            close()
        } catch (failure: Throwable) {
            failures += failure
        }
    }

    private class TypedRelationshipStore<Query : Any, Record : Any>(
        private val family: RelationTraversalFamily,
        limits: ServerLimits,
    ) : AutoCloseable {
        private val store = ServerHeldContinuationStore<
            String,
            Query,
            State<Record>,
            PageProjection<Record>,
        >(
            capacity = limits.typedContinuationCapacity,
            timeToLive = limits.typedContinuationTtl,
            tokenIssuer = ContinuationTokenIssuer { UUID.randomUUID().toString() },
            stateDisposer = ContinuationStateDisposer { },
        )

        fun page(
            query: Query,
            limit: Int,
            handle: RelationTraversalHandle?,
            initialRecords: List<Record>?,
            generation: Long,
        ): PublicPage<Record> = if (handle == null) {
            start(
                query,
                limit,
                requireNotNull(initialRecords) { "A first relationship page requires records" },
                generation,
            )
        } else {
            require(initialRecords == null) { "A continuation page must not restart provider work" }
            resume(query, limit, handle, generation)
        }

        override fun close() {
            store.close()
        }

        private fun start(
            query: Query,
            limit: Int,
            records: List<Record>,
            generation: Long,
        ): PublicPage<Record> {
            if (records.size > MAX_STATE_RECORDS) {
                throw traversalStateUnavailable(ContinuationAccessFailure.UnknownToken)
            }
            val page = records.take(limit)
            val remaining = records.drop(page.size).takeIf(List<Record>::isNotEmpty)
            val nextHandle = remaining?.let { rest ->
                when (val issued = store.issue(
                    query,
                    State(rest, page.size, generation),
                )) {
                    is ContinuationIssueResult.Issued ->
                        RelationTraversalHandle.create(family, issued.token)
                    is ContinuationIssueResult.Rejected ->
                        throw traversalStateUnavailable(issued.failure)
                }
            }
            return publicPage(
                records = page,
                returnedBefore = 0,
                cumulativeReturned = page.size,
                nextHandle = nextHandle,
            )
        }

        private fun resume(
            query: Query,
            limit: Int,
            handle: RelationTraversalHandle,
            generation: Long,
        ): PublicPage<Record> {
            if (handle.family != family) {
                throw continuationConflict("familyMismatch")
            }
            return when (val consumed = store.consume(
                token = handle.opaqueId,
                query = query,
                transition = ContinuationStateTransition { state ->
                    if (state.generation != generation) {
                        throw continuationConflict("generationChanged")
                    }
                    val returnedBefore = state.returnedBefore
                    val records = state.remaining.take(limit)
                    val remaining = state.remaining.drop(records.size)
                        .takeIf(List<Record>::isNotEmpty)
                    val cumulativeReturned = Math.addExact(returnedBefore, records.size)
                    val projection = PageProjection(
                        records,
                        returnedBefore,
                        cumulativeReturned,
                    )
                    if (remaining == null) {
                        ContinuationTransition.Complete(projection)
                    } else {
                        state.advance(remaining, cumulativeReturned)
                        ContinuationTransition.Reissue(projection, query)
                    }
                },
            )) {
                is ContinuationConsumeResult.Completed -> publicPage(
                    records = consumed.output.records,
                    returnedBefore = consumed.output.returnedBefore,
                    cumulativeReturned = consumed.output.cumulativeReturned,
                    nextHandle = null,
                )
                is ContinuationConsumeResult.Reissued -> publicPage(
                    records = consumed.output.records,
                    returnedBefore = consumed.output.returnedBefore,
                    cumulativeReturned = consumed.output.cumulativeReturned,
                    nextHandle = RelationTraversalHandle.create(family, consumed.token),
                )
                is ContinuationConsumeResult.Rejected -> throw when (consumed.failure) {
                    ContinuationAccessFailure.ExpiredToken -> continuationConflict("expired")
                    ContinuationAccessFailure.QueryMismatch -> continuationConflict("queryMismatch")
                    ContinuationAccessFailure.StoreClosed,
                    ContinuationAccessFailure.TokenCollision,
                    ContinuationAccessFailure.UnknownToken,
                    -> continuationConflict("unknown")
                }
            }
        }

        private fun publicPage(
            records: List<Record>,
            returnedBefore: Int,
            cumulativeReturned: Int,
            nextHandle: RelationTraversalHandle?,
        ): PublicPage<Record> {
            val hasMore = nextHandle != null
            val cardinality = if (hasMore) {
                ResultCardinality.KnownMinimum(Math.addExact(cumulativeReturned, 1))
            } else {
                ResultCardinality.Exact(cumulativeReturned)
            }
            val visitedCandidateCount = Math.addExact(records.size, if (hasMore) 1 else 0)
            return PublicPage(
                records,
                RelationTraversalPageInfo.create(
                    cardinality = cardinality,
                    returnedCount = records.size,
                    returnedBefore = returnedBefore,
                    visitedCandidateCount = visitedCandidateCount,
                    candidateVisitLimit = MAX_STATE_RECORDS,
                    nextHandle = nextHandle,
                ),
            )
        }

        private fun traversalStateUnavailable(
            failure: ContinuationAccessFailure,
        ): ConflictException = ConflictException(
            message = "Relationship continuation state is unavailable",
            details = mapOf(
                "continuationFailure" to when (failure) {
                    ContinuationAccessFailure.ExpiredToken -> "expired"
                    ContinuationAccessFailure.QueryMismatch -> "queryMismatch"
                    ContinuationAccessFailure.StoreClosed,
                    ContinuationAccessFailure.TokenCollision,
                    ContinuationAccessFailure.UnknownToken,
                    -> "traversalStateBudgetReached"
                },
            ),
        )

        private fun continuationConflict(reason: String): ConflictException = ConflictException(
            message = "Relationship continuation is stale, invalid, or belongs to another query",
            details = mapOf("continuationFailure" to reason),
        )
    }

    private class State<Record : Any>(
        remaining: List<Record>,
        returnedBefore: Int,
        val generation: Long,
    ) : ContinuationOwnedState() {
        var remaining: List<Record> = remaining
            private set
        var returnedBefore: Int = returnedBefore
            private set

        fun advance(remaining: List<Record>, returnedBefore: Int) {
            require(returnedBefore >= this.returnedBefore) {
                "Relationship continuation cardinality must not regress"
            }
            this.remaining = remaining
            this.returnedBefore = returnedBefore
        }
    }

    private data class PageProjection<Record : Any>(
        val records: List<Record>,
        val returnedBefore: Int,
        val cumulativeReturned: Int,
    ) : ContinuationProjection()

    private data class PublicPage<Record : Any>(
        val records: List<Record>,
        val pageInfo: RelationTraversalPageInfo,
    )

    private companion object {
        const val MAX_STATE_RECORDS: Int = 16_384
    }
}
