package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.IndexSyncQualification
import io.github.amichne.kast.protocol.contract.IndexSyncRejection
import io.github.amichne.kast.protocol.contract.IndexSyncRequest
import io.github.amichne.kast.protocol.contract.IndexSyncResult
import io.github.amichne.kast.protocol.contract.IndexSyncStateDocument

internal object CanonicalIndexSerializers {
    private val factory = GeneratedWireCodecFactory(wireJson)

    val request = factory.create(
        IndexSyncRequestDocument.serializer(),
        { IndexSyncRequestDocument },
        { WireDocumentConversion.Converted(IndexSyncRequest) },
    )
    val result = factory.create(
        IndexSyncResultDocument.serializer(),
        { result -> IndexSyncResultDocument(result.state.toWire()) },
        { document -> WireDocumentConversion.Converted(IndexSyncResult(document.state.toContract())) },
    )
    val qualification = factory.create(
        IndexSyncQualificationWireDocument.serializer(),
        IndexSyncQualification::toWire,
        { document -> WireDocumentConversion.Converted(document.toContract()) },
    )
    val rejection = factory.create(
        IndexSyncRejectionWireDocument.serializer(),
        IndexSyncRejection::toWire,
        { document -> WireDocumentConversion.Converted(document.toContract()) },
    )
}

private fun IndexSyncStateDocument.toWire(): IndexSyncStateWireDocument = when (this) {
    IndexSyncStateDocument.SYNCHRONIZED -> IndexSyncStateWireDocument.SYNCHRONIZED
    IndexSyncStateDocument.UNCHANGED -> IndexSyncStateWireDocument.UNCHANGED
}

private fun IndexSyncStateWireDocument.toContract(): IndexSyncStateDocument = when (this) {
    IndexSyncStateWireDocument.SYNCHRONIZED -> IndexSyncStateDocument.SYNCHRONIZED
    IndexSyncStateWireDocument.UNCHANGED -> IndexSyncStateDocument.UNCHANGED
}

private fun IndexSyncQualification.toWire(): IndexSyncQualificationWireDocument = when (this) {
    IndexSyncQualification.INDEXING_IN_PROGRESS ->
        IndexSyncQualificationWireDocument.INDEXING_IN_PROGRESS
}

private fun IndexSyncQualificationWireDocument.toContract(): IndexSyncQualification = when (this) {
    IndexSyncQualificationWireDocument.INDEXING_IN_PROGRESS ->
        IndexSyncQualification.INDEXING_IN_PROGRESS
}

private fun IndexSyncRejection.toWire(): IndexSyncRejectionWireDocument = when (this) {
    IndexSyncRejection.WORKSPACE_NOT_READY -> IndexSyncRejectionWireDocument.WORKSPACE_NOT_READY
    IndexSyncRejection.INVALID_SOURCE_ROOT_SCOPE ->
        IndexSyncRejectionWireDocument.INVALID_SOURCE_ROOT_SCOPE
    IndexSyncRejection.REFRESH_UNAVAILABLE -> IndexSyncRejectionWireDocument.REFRESH_UNAVAILABLE
    IndexSyncRejection.INDEXING_INTERRUPTED -> IndexSyncRejectionWireDocument.INDEXING_INTERRUPTED
    IndexSyncRejection.INDEXING_TIMED_OUT -> IndexSyncRejectionWireDocument.INDEXING_TIMED_OUT
    IndexSyncRejection.INDEXING_FAILED -> IndexSyncRejectionWireDocument.INDEXING_FAILED
    IndexSyncRejection.PUBLICATION_INVALIDATED ->
        IndexSyncRejectionWireDocument.PUBLICATION_INVALIDATED
    IndexSyncRejection.PUBLICATION_BLOCKED -> IndexSyncRejectionWireDocument.PUBLICATION_BLOCKED
    IndexSyncRejection.PUBLICATION_CONTRACT_VIOLATION ->
        IndexSyncRejectionWireDocument.PUBLICATION_CONTRACT_VIOLATION
}

private fun IndexSyncRejectionWireDocument.toContract(): IndexSyncRejection = when (this) {
    IndexSyncRejectionWireDocument.WORKSPACE_NOT_READY -> IndexSyncRejection.WORKSPACE_NOT_READY
    IndexSyncRejectionWireDocument.INVALID_SOURCE_ROOT_SCOPE ->
        IndexSyncRejection.INVALID_SOURCE_ROOT_SCOPE
    IndexSyncRejectionWireDocument.REFRESH_UNAVAILABLE -> IndexSyncRejection.REFRESH_UNAVAILABLE
    IndexSyncRejectionWireDocument.INDEXING_INTERRUPTED -> IndexSyncRejection.INDEXING_INTERRUPTED
    IndexSyncRejectionWireDocument.INDEXING_TIMED_OUT -> IndexSyncRejection.INDEXING_TIMED_OUT
    IndexSyncRejectionWireDocument.INDEXING_FAILED -> IndexSyncRejection.INDEXING_FAILED
    IndexSyncRejectionWireDocument.PUBLICATION_INVALIDATED ->
        IndexSyncRejection.PUBLICATION_INVALIDATED
    IndexSyncRejectionWireDocument.PUBLICATION_BLOCKED -> IndexSyncRejection.PUBLICATION_BLOCKED
    IndexSyncRejectionWireDocument.PUBLICATION_CONTRACT_VIOLATION ->
        IndexSyncRejection.PUBLICATION_CONTRACT_VIOLATION
}
