package io.github.amichne.kast.relation.contract

/** Page limits can clear on continuation; omitted semantic evidence cannot. */
val RelationRequest.retainedLimitations: Set<RelationLimitation>
    get() =
        when (val read = position) {
            RelationReadPosition.Start -> emptySet()
            is RelationReadPosition.Resume -> read.continuation.retainedLimitations
        }

internal val relationPageLimits =
    setOf(
        RelationLimitation.RESULT_LIMIT_REACHED,
        RelationLimitation.BYTE_LIMIT_REACHED,
        RelationLimitation.WORK_LIMIT_REACHED,
        RelationLimitation.TIME_LIMIT_REACHED,
    )
