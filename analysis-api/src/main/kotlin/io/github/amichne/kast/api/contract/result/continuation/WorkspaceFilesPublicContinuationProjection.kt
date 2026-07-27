package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.continuation.ContinuationProjection
import io.github.amichne.kast.api.contract.query.WorkspaceFilesPublicContinuationIdentity
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceFilesPublicContinuationProjection(
    @DocField(description = "Exact public query identity bound to the consumed continuation.")
    val identity: WorkspaceFilesPublicContinuationIdentity,
    @DocField(description = "Lowercase SHA-256 digest of the coherent multi-source composition stamp.")
    val compositionStampDigest: WorkspaceFilesPublicContinuationState.CompositionStampDigest,
    @DocField(description = "Last normalized workspace-relative path returned before this continuation.")
    val lastRelativePath: WorkspaceFilesPublicContinuationState.LastRelativePath,
    @DocField(description = "Total number of file records returned before this continuation was consumed.")
    val cumulativeReturnedCount: WorkspaceFilesPublicContinuationState.CumulativeReturnedCount,
) : ContinuationProjection()
