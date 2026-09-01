package io.github.amichne.kast.protocol.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data object IndexSyncRequestDocument

@Serializable
internal data class IndexSyncResultDocument(
    val state: IndexSyncStateWireDocument,
)

@Serializable
internal enum class IndexSyncStateWireDocument {
    @SerialName("synchronized") SYNCHRONIZED,
    @SerialName("unchanged") UNCHANGED,
}

@Serializable
internal enum class IndexSyncQualificationWireDocument {
    @SerialName("indexing_in_progress") INDEXING_IN_PROGRESS,
}

@Serializable
internal enum class IndexSyncRejectionWireDocument {
    @SerialName("workspace_not_ready") WORKSPACE_NOT_READY,
    @SerialName("invalid_source_root_scope") INVALID_SOURCE_ROOT_SCOPE,
    @SerialName("refresh_unavailable") REFRESH_UNAVAILABLE,
    @SerialName("indexing_interrupted") INDEXING_INTERRUPTED,
    @SerialName("indexing_timed_out") INDEXING_TIMED_OUT,
    @SerialName("indexing_failed") INDEXING_FAILED,
    @SerialName("publication_invalidated") PUBLICATION_INVALIDATED,
    @SerialName("publication_blocked") PUBLICATION_BLOCKED,
    @SerialName("publication_contract_violation") PUBLICATION_CONTRACT_VIOLATION,
}
