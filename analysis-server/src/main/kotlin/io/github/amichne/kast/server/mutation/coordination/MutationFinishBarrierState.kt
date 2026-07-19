package io.github.amichne.kast.server.mutation.coordination

internal enum class MutationFinishBarrierState {
    DRAINED,
    REOPENED,
    COMPLETE,
    ABSENT,
}
