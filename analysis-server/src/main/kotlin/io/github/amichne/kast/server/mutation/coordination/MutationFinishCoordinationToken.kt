package io.github.amichne.kast.server.mutation.coordination

import java.util.UUID

@JvmInline
internal value class MutationFinishCoordinationToken(
    val value: String,
) {
    init {
        require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
            "Finish coordination token must be a UUID"
        }
    }
}
