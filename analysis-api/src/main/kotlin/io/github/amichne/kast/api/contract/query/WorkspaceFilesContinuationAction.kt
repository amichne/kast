package io.github.amichne.kast.api.contract.query

import kotlinx.serialization.Serializable

@Serializable
enum class WorkspaceFilesContinuationAction {
    ISSUE,
    CONSUME,
}
