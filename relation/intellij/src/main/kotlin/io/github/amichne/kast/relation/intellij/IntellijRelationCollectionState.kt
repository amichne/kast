package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination

/** Closed native provider termination; only [Terminal] can prove exact enumeration. */
sealed interface IntellijRelationTermination {
    data object Terminal : IntellijRelationTermination

    data class TerminalIncomplete(val limitations: Set<RelationLimitation>) : IntellijRelationTermination

    data class Resumable(val limitations: Set<RelationLimitation>) : IntellijRelationTermination
}

internal enum class IntellijRelationProviderItemAdmission {
    READY,
    HALTED,
}

internal enum class IntellijRelationProviderEnumerationAdmission {
    READY,
    HALTED,
}

internal enum class IntellijRelationCollectionState {
    COLLECTING,
    HALTED,
    ENUMERATION_LIMIT,
    CONTRACT_REJECTED,
}

internal const val MAX_NATIVE_RELATION_CANDIDATES = 10_000

internal fun RelationLimitation.observedTermination(): IntellijReadTermination =
    when (this) {
        RelationLimitation.RESULT_LIMIT_REACHED -> IntellijReadTermination.RESULT_LIMIT
        RelationLimitation.BYTE_LIMIT_REACHED -> IntellijReadTermination.BYTE_LIMIT
        RelationLimitation.WORK_LIMIT_REACHED -> IntellijReadTermination.WORK_LIMIT
        RelationLimitation.TIME_LIMIT_REACHED -> IntellijReadTermination.TIME_LIMIT
        RelationLimitation.DUMB_MODE_TRANSITION -> IntellijReadTermination.INDEXING
        RelationLimitation.UNRESOLVED_TARGET -> IntellijReadTermination.RELATION_UNRESOLVED_TARGET
        RelationLimitation.UNSUPPORTED_ITEM -> IntellijReadTermination.RELATION_UNSUPPORTED_ITEM
        RelationLimitation.PROVIDER_FAILURE -> IntellijReadTermination.PROVIDER_FAILURE
        RelationLimitation.PROVIDER_INCOMPLETE -> IntellijReadTermination.RELATION_PROVIDER_INCOMPLETE
        RelationLimitation.PROVIDER_STALLED -> IntellijReadTermination.RELATION_PROVIDER_STALLED
        RelationLimitation.CANDIDATE_LIMIT_REACHED -> IntellijReadTermination.CANDIDATE_CAP
        RelationLimitation.RETENTION_LIMIT_REACHED -> IntellijReadTermination.RETENTION_LIMIT
        RelationLimitation.PARTITION_INVENTORY_UNAVAILABLE -> IntellijReadTermination.RELATION_PROVIDER_INCOMPLETE
    }
