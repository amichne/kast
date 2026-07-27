package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.validation.WorkspaceFilesPublicPageToken
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface WorkspaceFilesContinuationResult {
    @Serializable
    @SerialName("ISSUED")
    data class Issued(
        @DocField(description = "Opaque single-use public continuation handle owned by the server.")
        val pageToken: WorkspaceFilesPublicPageToken,
    ) : WorkspaceFilesContinuationResult

    @Serializable
    @SerialName("CONSUMED")
    data class Consumed(
        @DocField(description = "Non-owning projection of the consumed server-held continuation state.")
        val state: WorkspaceFilesPublicContinuationProjection,
    ) : WorkspaceFilesContinuationResult
}
