package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.result.WorkspaceFilesPublicContinuationState
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFilesPageTokenException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.WorkspaceFilesPublicPageToken
import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceFilesContinuationQuery(
    val action: WorkspaceFilesContinuationAction,
    val identity: WorkspaceFilesPublicContinuationIdentity,
    val state: WorkspaceFilesPublicContinuationState? = null,
    val pageToken: String? = null,
) {
    fun parsed(): Parsed = when (action) {
        WorkspaceFilesContinuationAction.ISSUE -> {
            val issueState = state
                ?: throw ValidationException("Issuing a workspace-file continuation requires state")
            if (pageToken != null) {
                throw ValidationException("Issuing a workspace-file continuation cannot consume a page token")
            }
            if (issueState.identity != identity) {
                throw ValidationException("Workspace-file continuation state must match its query identity")
            }
            Parsed.Issue(identity, issueState)
        }

        WorkspaceFilesContinuationAction.CONSUME -> {
            if (state != null) {
                throw ValidationException("Consuming a workspace-file continuation cannot replace stored state")
            }
            val rawToken = pageToken
                ?: throw ValidationException("Consuming a workspace-file continuation requires a page token")
            val token = try {
                WorkspaceFilesPublicPageToken.parse(rawToken)
            } catch (failure: IllegalArgumentException) {
                throw InvalidWorkspaceFilesPageTokenException(
                    failure.message ?: "Invalid workspace-files public page token",
                )
            }
            Parsed.Consume(identity, token)
        }
    }

    sealed interface Parsed {
        val identity: WorkspaceFilesPublicContinuationIdentity

        data class Issue(
            override val identity: WorkspaceFilesPublicContinuationIdentity,
            val state: WorkspaceFilesPublicContinuationState,
        ) : Parsed

        data class Consume(
            override val identity: WorkspaceFilesPublicContinuationIdentity,
            val pageToken: WorkspaceFilesPublicPageToken,
        ) : Parsed
    }

    companion object {
        fun issue(
            identity: WorkspaceFilesPublicContinuationIdentity,
            state: WorkspaceFilesPublicContinuationState,
        ): WorkspaceFilesContinuationQuery = WorkspaceFilesContinuationQuery(
            action = WorkspaceFilesContinuationAction.ISSUE,
            identity = identity,
            state = state,
        )

        fun consume(
            identity: WorkspaceFilesPublicContinuationIdentity,
            pageToken: WorkspaceFilesPublicPageToken,
        ): WorkspaceFilesContinuationQuery = WorkspaceFilesContinuationQuery(
            action = WorkspaceFilesContinuationAction.CONSUME,
            identity = identity,
            pageToken = pageToken.value,
        )
    }
}
