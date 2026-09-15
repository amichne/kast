package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument

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
