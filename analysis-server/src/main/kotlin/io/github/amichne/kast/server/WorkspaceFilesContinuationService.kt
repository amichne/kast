package io.github.amichne.kast.server

import io.github.amichne.kast.api.continuation.ContinuationAccessFailure
import io.github.amichne.kast.api.continuation.ContinuationCapacity
import io.github.amichne.kast.api.continuation.ContinuationClock
import io.github.amichne.kast.api.continuation.ContinuationConsumeResult
import io.github.amichne.kast.api.continuation.ContinuationIssueResult
import io.github.amichne.kast.api.continuation.ContinuationStateDisposer
import io.github.amichne.kast.api.continuation.ContinuationStateTransition
import io.github.amichne.kast.api.continuation.ContinuationTokenIssuer
import io.github.amichne.kast.api.continuation.ContinuationTransition
import io.github.amichne.kast.api.continuation.ContinuationTtl
import io.github.amichne.kast.api.continuation.ServerHeldContinuationStore
import io.github.amichne.kast.api.contract.query.WorkspaceFilesContinuationQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesPublicContinuationIdentity
import io.github.amichne.kast.api.contract.result.WorkspaceFilesContinuationResult
import io.github.amichne.kast.api.contract.result.WorkspaceFilesPublicContinuationProjection
import io.github.amichne.kast.api.contract.result.WorkspaceFilesPublicContinuationState
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFilesPageTokenException
import io.github.amichne.kast.api.validation.WorkspaceFilesPublicPageToken
import java.io.Closeable

internal class WorkspaceFilesContinuationService(
    capacity: ContinuationCapacity,
    timeToLive: ContinuationTtl,
    tokenIssuer: ContinuationTokenIssuer<WorkspaceFilesPublicPageToken> =
        ContinuationTokenIssuer(WorkspaceFilesPublicPageToken::random),
    clock: ContinuationClock = ContinuationClock.System,
) : Closeable {
    private val store = ServerHeldContinuationStore<
        WorkspaceFilesPublicPageToken,
        WorkspaceFilesPublicContinuationIdentity,
        WorkspaceFilesPublicContinuationState,
        WorkspaceFilesPublicContinuationProjection,
        >(
        capacity = capacity,
        timeToLive = timeToLive,
        tokenIssuer = tokenIssuer,
        stateDisposer = ContinuationStateDisposer { },
        clock = clock,
    )

    fun execute(query: WorkspaceFilesContinuationQuery.Parsed): WorkspaceFilesContinuationResult = when (query) {
        is WorkspaceFilesContinuationQuery.Parsed.Issue -> issue(query)
        is WorkspaceFilesContinuationQuery.Parsed.Consume -> consume(query)
    }

    override fun close() {
        store.close()
    }

    private fun issue(
        query: WorkspaceFilesContinuationQuery.Parsed.Issue,
    ): WorkspaceFilesContinuationResult = when (val result = store.issue(query.identity, query.state)) {
        is ContinuationIssueResult.Issued -> WorkspaceFilesContinuationResult.Issued(result.token)
        is ContinuationIssueResult.Rejected -> throw invalidToken(result.failure)
    }

    private fun consume(
        query: WorkspaceFilesContinuationQuery.Parsed.Consume,
    ): WorkspaceFilesContinuationResult = when (
        val result = store.consume(
            token = query.pageToken,
            query = query.identity,
            transition = ContinuationStateTransition { state ->
                ContinuationTransition.Complete(state.toProjection())
            },
        )
    ) {
        is ContinuationConsumeResult.Completed -> WorkspaceFilesContinuationResult.Consumed(result.output)
        is ContinuationConsumeResult.Reissued -> error("Public workspace-file continuation consumption cannot reissue")
        is ContinuationConsumeResult.Rejected -> throw invalidToken(result.failure)
    }

    private fun invalidToken(failure: ContinuationAccessFailure): InvalidWorkspaceFilesPageTokenException =
        InvalidWorkspaceFilesPageTokenException(
            "Invalid workspace-files public page token (${failure::class.simpleName})",
        )
}
