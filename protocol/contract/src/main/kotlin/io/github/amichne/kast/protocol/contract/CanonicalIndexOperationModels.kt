package io.github.amichne.kast.protocol.contract

data object IndexSyncRequest : OperationRequest

data class IndexSyncResult(
    val state: IndexSyncStateDocument,
) : OperationResult

enum class IndexSyncStateDocument {
    SYNCHRONIZED,
    UNCHANGED,
}

/** Reserved typed qualification; index synchronization currently requires complete evidence. */
enum class IndexSyncQualification : OperationQualification {
    INDEXING_IN_PROGRESS,
}

enum class IndexSyncRejection : OperationRejection {
    WORKSPACE_NOT_READY,
    INVALID_SOURCE_ROOT_SCOPE,
    REFRESH_UNAVAILABLE,
    INDEXING_INTERRUPTED,
    INDEXING_TIMED_OUT,
    INDEXING_FAILED,
    PUBLICATION_INVALIDATED,
    PUBLICATION_BLOCKED,
    PUBLICATION_CONTRACT_VIOLATION,
}
