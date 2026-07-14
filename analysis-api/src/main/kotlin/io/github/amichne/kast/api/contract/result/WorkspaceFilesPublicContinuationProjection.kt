package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.continuation.ContinuationProjection
import io.github.amichne.kast.api.contract.query.WorkspaceFilesPublicContinuationIdentity
import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceFilesPublicContinuationProjection(
    val identity: WorkspaceFilesPublicContinuationIdentity,
    val compositionStampDigest: WorkspaceFilesPublicContinuationState.CompositionStampDigest,
    val lastRelativePath: WorkspaceFilesPublicContinuationState.LastRelativePath,
    val cumulativeReturnedCount: WorkspaceFilesPublicContinuationState.CumulativeReturnedCount,
) : ContinuationProjection()
