package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.validation.WorkspaceFilesPublicPageToken
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface WorkspaceFilesContinuationResult {
    @Serializable
    @SerialName("ISSUED")
    data class Issued(val pageToken: WorkspaceFilesPublicPageToken) : WorkspaceFilesContinuationResult

    @Serializable
    @SerialName("CONSUMED")
    data class Consumed(
        val state: WorkspaceFilesPublicContinuationProjection,
    ) : WorkspaceFilesContinuationResult
}
