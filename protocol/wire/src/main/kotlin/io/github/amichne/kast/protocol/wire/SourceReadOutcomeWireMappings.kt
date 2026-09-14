package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadRejection

internal fun SourceReadRejection.toWireDocument(): SourceReadRejectionWireDocument =
    when (this) {
        SourceReadRejection.CONTINUATION_UNAVAILABLE -> SourceReadRejectionWireDocument.CONTINUATION_UNAVAILABLE
        SourceReadRejection.CONTINUATION_REQUEST_MISMATCH ->
            SourceReadRejectionWireDocument.CONTINUATION_REQUEST_MISMATCH
        SourceReadRejection.WORKSPACE_NOT_READY -> SourceReadRejectionWireDocument.WORKSPACE_NOT_READY
        SourceReadRejection.WORKSPACE_ROOT_MISMATCH -> SourceReadRejectionWireDocument.WORKSPACE_ROOT_MISMATCH
        SourceReadRejection.STALE_GENERATION -> SourceReadRejectionWireDocument.STALE_GENERATION
        SourceReadRejection.SOURCE_STATE_MISMATCH -> SourceReadRejectionWireDocument.SOURCE_STATE_MISMATCH
        SourceReadRejection.CANDIDATE_STALE -> SourceReadRejectionWireDocument.CANDIDATE_STALE
        SourceReadRejection.SOURCE_SELECTOR_STALE -> SourceReadRejectionWireDocument.SOURCE_SELECTOR_STALE
        SourceReadRejection.SOURCE_SNAPSHOT_MISMATCH -> SourceReadRejectionWireDocument.SOURCE_SNAPSHOT_MISMATCH
        SourceReadRejection.SOURCE_UNAVAILABLE -> SourceReadRejectionWireDocument.SOURCE_UNAVAILABLE
        SourceReadRejection.DOCUMENT_DIRTY -> SourceReadRejectionWireDocument.DOCUMENT_DIRTY
        SourceReadRejection.PSI_DOCUMENT_UNCOMMITTED -> SourceReadRejectionWireDocument.PSI_DOCUMENT_UNCOMMITTED
        SourceReadRejection.OUTSIDE_SOURCE_SCOPE -> SourceReadRejectionWireDocument.OUTSIDE_SOURCE_SCOPE
        SourceReadRejection.ANCHOR_NOT_FOUND -> SourceReadRejectionWireDocument.ANCHOR_NOT_FOUND
        SourceReadRejection.AMBIGUOUS_ANCHOR -> SourceReadRejectionWireDocument.AMBIGUOUS_ANCHOR
        SourceReadRejection.REGION_NOT_APPLICABLE -> SourceReadRejectionWireDocument.REGION_NOT_APPLICABLE
        SourceReadRejection.REGION_ABSENT -> SourceReadRejectionWireDocument.REGION_ABSENT
        SourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE ->
            SourceReadRejectionWireDocument.COMPILER_ANALYSIS_UNAVAILABLE
        SourceReadRejection.CONTRACT_VIOLATION -> SourceReadRejectionWireDocument.CONTRACT_VIOLATION
    }

internal fun SourceReadRejectionWireDocument.toContract(): SourceReadRejection =
    when (this) {
        SourceReadRejectionWireDocument.CONTINUATION_UNAVAILABLE -> SourceReadRejection.CONTINUATION_UNAVAILABLE
        SourceReadRejectionWireDocument.CONTINUATION_REQUEST_MISMATCH ->
            SourceReadRejection.CONTINUATION_REQUEST_MISMATCH
        SourceReadRejectionWireDocument.WORKSPACE_NOT_READY -> SourceReadRejection.WORKSPACE_NOT_READY
        SourceReadRejectionWireDocument.WORKSPACE_ROOT_MISMATCH -> SourceReadRejection.WORKSPACE_ROOT_MISMATCH
        SourceReadRejectionWireDocument.STALE_GENERATION -> SourceReadRejection.STALE_GENERATION
        SourceReadRejectionWireDocument.SOURCE_STATE_MISMATCH -> SourceReadRejection.SOURCE_STATE_MISMATCH
        SourceReadRejectionWireDocument.CANDIDATE_STALE -> SourceReadRejection.CANDIDATE_STALE
        SourceReadRejectionWireDocument.SOURCE_SELECTOR_STALE -> SourceReadRejection.SOURCE_SELECTOR_STALE
        SourceReadRejectionWireDocument.SOURCE_SNAPSHOT_MISMATCH -> SourceReadRejection.SOURCE_SNAPSHOT_MISMATCH
        SourceReadRejectionWireDocument.SOURCE_UNAVAILABLE -> SourceReadRejection.SOURCE_UNAVAILABLE
        SourceReadRejectionWireDocument.DOCUMENT_DIRTY -> SourceReadRejection.DOCUMENT_DIRTY
        SourceReadRejectionWireDocument.PSI_DOCUMENT_UNCOMMITTED -> SourceReadRejection.PSI_DOCUMENT_UNCOMMITTED
        SourceReadRejectionWireDocument.OUTSIDE_SOURCE_SCOPE -> SourceReadRejection.OUTSIDE_SOURCE_SCOPE
        SourceReadRejectionWireDocument.ANCHOR_NOT_FOUND -> SourceReadRejection.ANCHOR_NOT_FOUND
        SourceReadRejectionWireDocument.AMBIGUOUS_ANCHOR -> SourceReadRejection.AMBIGUOUS_ANCHOR
        SourceReadRejectionWireDocument.REGION_NOT_APPLICABLE -> SourceReadRejection.REGION_NOT_APPLICABLE
        SourceReadRejectionWireDocument.REGION_ABSENT -> SourceReadRejection.REGION_ABSENT
        SourceReadRejectionWireDocument.COMPILER_ANALYSIS_UNAVAILABLE ->
            SourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE
        SourceReadRejectionWireDocument.CONTRACT_VIOLATION -> SourceReadRejection.CONTRACT_VIOLATION
    }

internal fun SourceReadLimitationDocument.toWireDocument(): SourceReadLimitationWireDocument =
    when (this) {
        SourceReadLimitationDocument.RETURNED_BYTE_LIMIT_REACHED ->
            SourceReadLimitationWireDocument.RETURNED_BYTE_LIMIT_REACHED
        SourceReadLimitationDocument.ENTITY_LIMIT_REACHED -> SourceReadLimitationWireDocument.ENTITY_LIMIT_REACHED
        SourceReadLimitationDocument.TEXT_BYTE_LIMIT_REACHED -> SourceReadLimitationWireDocument.TEXT_BYTE_LIMIT_REACHED
        SourceReadLimitationDocument.WORK_LIMIT_REACHED -> SourceReadLimitationWireDocument.WORK_LIMIT_REACHED
        SourceReadLimitationDocument.TIME_LIMIT_REACHED -> SourceReadLimitationWireDocument.TIME_LIMIT_REACHED
        SourceReadLimitationDocument.DUMB_MODE_TRANSITION -> SourceReadLimitationWireDocument.DUMB_MODE_TRANSITION
        SourceReadLimitationDocument.SEMANTIC_RESOLUTION_INCOMPLETE ->
            SourceReadLimitationWireDocument.SEMANTIC_RESOLUTION_INCOMPLETE
        SourceReadLimitationDocument.UNSUPPORTED_ENTITY -> SourceReadLimitationWireDocument.UNSUPPORTED_ENTITY
        SourceReadLimitationDocument.PROVIDER_FAILURE -> SourceReadLimitationWireDocument.PROVIDER_FAILURE
    }

internal fun SourceReadLimitationWireDocument.toContract(): SourceReadLimitationDocument =
    when (this) {
        SourceReadLimitationWireDocument.RETURNED_BYTE_LIMIT_REACHED ->
            SourceReadLimitationDocument.RETURNED_BYTE_LIMIT_REACHED
        SourceReadLimitationWireDocument.ENTITY_LIMIT_REACHED -> SourceReadLimitationDocument.ENTITY_LIMIT_REACHED
        SourceReadLimitationWireDocument.TEXT_BYTE_LIMIT_REACHED -> SourceReadLimitationDocument.TEXT_BYTE_LIMIT_REACHED
        SourceReadLimitationWireDocument.WORK_LIMIT_REACHED -> SourceReadLimitationDocument.WORK_LIMIT_REACHED
        SourceReadLimitationWireDocument.TIME_LIMIT_REACHED -> SourceReadLimitationDocument.TIME_LIMIT_REACHED
        SourceReadLimitationWireDocument.DUMB_MODE_TRANSITION -> SourceReadLimitationDocument.DUMB_MODE_TRANSITION
        SourceReadLimitationWireDocument.SEMANTIC_RESOLUTION_INCOMPLETE ->
            SourceReadLimitationDocument.SEMANTIC_RESOLUTION_INCOMPLETE
        SourceReadLimitationWireDocument.UNSUPPORTED_ENTITY -> SourceReadLimitationDocument.UNSUPPORTED_ENTITY
        SourceReadLimitationWireDocument.PROVIDER_FAILURE -> SourceReadLimitationDocument.PROVIDER_FAILURE
    }
