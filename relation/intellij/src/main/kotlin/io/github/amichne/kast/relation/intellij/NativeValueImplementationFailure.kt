package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause

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

internal fun RelationCompilerRejection.nativeFlowRejection(): ValueFlowRejection =
    when (this) {
        RelationCompilerRejection.WORKSPACE_ROOT_MISMATCH,
        RelationCompilerRejection.GENERATION_MOVED -> ValueFlowRejection.AUTHORITY_MOVED
        RelationCompilerRejection.SCOPE_REJECTED,
        RelationCompilerRejection.OUTSIDE_SCOPE -> ValueFlowRejection.OUTSIDE_DOMAIN
        RelationCompilerRejection.STALE_SELECTOR,
        RelationCompilerRejection.CONTINUATION_CURSOR_MOVED -> ValueFlowRejection.STALE_SITE
        RelationCompilerRejection.AMBIGUOUS_SUBJECT,
        RelationCompilerRejection.UNSUPPORTED_SUBJECT,
        RelationCompilerRejection.COMPILER_IDENTITY_UNAVAILABLE -> ValueFlowRejection.OWNER_UNAVAILABLE
        RelationCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE,
        RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION -> ValueFlowRejection.NATIVE_UNAVAILABLE
    }
