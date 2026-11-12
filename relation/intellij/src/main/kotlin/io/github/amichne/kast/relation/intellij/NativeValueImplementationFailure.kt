package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueNativeRelationOrigin

internal fun RelationLimitation.nativeImplementationCause(): ValueFlowUnsupportedCause =
    when (this) {
        RelationLimitation.RESULT_LIMIT_REACHED -> ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED
        RelationLimitation.BYTE_LIMIT_REACHED -> ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED
        RelationLimitation.WORK_LIMIT_REACHED -> ValueFlowUnsupportedCause.WORK_LIMIT_REACHED
        RelationLimitation.TIME_LIMIT_REACHED -> ValueFlowUnsupportedCause.TIME_LIMIT_REACHED
        RelationLimitation.DUMB_MODE_TRANSITION,
        RelationLimitation.UNRESOLVED_TARGET,
        RelationLimitation.UNSUPPORTED_ITEM,
        RelationLimitation.PROVIDER_FAILURE,
        RelationLimitation.PROVIDER_INCOMPLETE,
        RelationLimitation.PROVIDER_STALLED,
        RelationLimitation.CANDIDATE_LIMIT_REACHED,
        RelationLimitation.RETENTION_LIMIT_REACHED,
        RelationLimitation.PARTITION_INVENTORY_UNAVAILABLE -> ValueFlowUnsupportedCause.UNMODELED_CALL
    }

/** Preserve the received cause; WORKSPACE_INDEX_UNAVAILABLE may already conceal an upstream exception. */
internal fun RelationCompilerRejection.nativeFlowRejection(
    origin: ValueNativeRelationOrigin
): ValueFlowRejection.Native = ValueFlowRejection.Native.RelationRejected(origin, this)
