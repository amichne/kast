package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument

internal fun RelationKindDocument.toWireDocument(): RelationKindWireDocument =
    when (this) {
        RelationKindDocument.REFERENCES -> RelationKindWireDocument.REFERENCES
        RelationKindDocument.CALLERS -> RelationKindWireDocument.CALLERS
        RelationKindDocument.CALLEES -> RelationKindWireDocument.CALLEES
        RelationKindDocument.IMPLEMENTATIONS -> RelationKindWireDocument.IMPLEMENTATIONS
        RelationKindDocument.INHERITORS -> RelationKindWireDocument.INHERITORS
        RelationKindDocument.OVERRIDES -> RelationKindWireDocument.OVERRIDES
        RelationKindDocument.TYPE_USES -> RelationKindWireDocument.TYPE_USES
    }

internal fun RelationKindWireDocument.toContract(): RelationKindDocument =
    when (this) {
        RelationKindWireDocument.REFERENCES -> RelationKindDocument.REFERENCES
        RelationKindWireDocument.CALLERS -> RelationKindDocument.CALLERS
        RelationKindWireDocument.CALLEES -> RelationKindDocument.CALLEES
        RelationKindWireDocument.IMPLEMENTATIONS -> RelationKindDocument.IMPLEMENTATIONS
        RelationKindWireDocument.INHERITORS -> RelationKindDocument.INHERITORS
        RelationKindWireDocument.OVERRIDES -> RelationKindDocument.OVERRIDES
        RelationKindWireDocument.TYPE_USES -> RelationKindDocument.TYPE_USES
    }

internal fun RelationLimitationDocument.toWireDocument(): RelationLimitationWireDocument =
    when (this) {
        RelationLimitationDocument.RESULT_LIMIT_REACHED -> RelationLimitationWireDocument.RESULT_LIMIT_REACHED
        RelationLimitationDocument.BYTE_LIMIT_REACHED -> RelationLimitationWireDocument.BYTE_LIMIT_REACHED
        RelationLimitationDocument.WORK_LIMIT_REACHED -> RelationLimitationWireDocument.WORK_LIMIT_REACHED
        RelationLimitationDocument.TIME_LIMIT_REACHED -> RelationLimitationWireDocument.TIME_LIMIT_REACHED
        RelationLimitationDocument.DUMB_MODE_TRANSITION -> RelationLimitationWireDocument.DUMB_MODE_TRANSITION
        RelationLimitationDocument.UNRESOLVED_TARGET -> RelationLimitationWireDocument.UNRESOLVED_TARGET
        RelationLimitationDocument.UNSUPPORTED_ITEM -> RelationLimitationWireDocument.UNSUPPORTED_ITEM
        RelationLimitationDocument.PROVIDER_FAILURE -> RelationLimitationWireDocument.PROVIDER_FAILURE
        RelationLimitationDocument.PROVIDER_INCOMPLETE -> RelationLimitationWireDocument.PROVIDER_INCOMPLETE
        RelationLimitationDocument.PROVIDER_STALLED -> RelationLimitationWireDocument.PROVIDER_STALLED
    }

internal fun RelationLimitationWireDocument.toContract(): RelationLimitationDocument =
    when (this) {
        RelationLimitationWireDocument.RESULT_LIMIT_REACHED -> RelationLimitationDocument.RESULT_LIMIT_REACHED
        RelationLimitationWireDocument.BYTE_LIMIT_REACHED -> RelationLimitationDocument.BYTE_LIMIT_REACHED
        RelationLimitationWireDocument.WORK_LIMIT_REACHED -> RelationLimitationDocument.WORK_LIMIT_REACHED
        RelationLimitationWireDocument.TIME_LIMIT_REACHED -> RelationLimitationDocument.TIME_LIMIT_REACHED
        RelationLimitationWireDocument.DUMB_MODE_TRANSITION -> RelationLimitationDocument.DUMB_MODE_TRANSITION
        RelationLimitationWireDocument.UNRESOLVED_TARGET -> RelationLimitationDocument.UNRESOLVED_TARGET
        RelationLimitationWireDocument.UNSUPPORTED_ITEM -> RelationLimitationDocument.UNSUPPORTED_ITEM
        RelationLimitationWireDocument.PROVIDER_FAILURE -> RelationLimitationDocument.PROVIDER_FAILURE
        RelationLimitationWireDocument.PROVIDER_INCOMPLETE -> RelationLimitationDocument.PROVIDER_INCOMPLETE
        RelationLimitationWireDocument.PROVIDER_STALLED -> RelationLimitationDocument.PROVIDER_STALLED
    }
