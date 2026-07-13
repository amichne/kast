package io.github.amichne.kast.api.contract.mutation

import kotlinx.serialization.Serializable

@Serializable
enum class KastMutationEditApplicationState {
    NOT_STARTED,
    STARTED,
    COMPLETED,
}
