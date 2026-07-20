package io.github.amichne.kast.api.contract.mutation

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
@JvmInline
value class KastWorkspaceTaskId(
    val value: String,
) {
    init {
        require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
            "Workspace task ID must be a UUID"
        }
    }

    override fun toString(): String = value
}
