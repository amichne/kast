package io.github.amichne.kast.api.validation

import java.util.UUID

@JvmInline
value class WorkspaceFilePageToken private constructor(val value: String) {
    companion object {
        fun parse(value: String): WorkspaceFilePageToken {
            val parsed = UUID.fromString(value)
            require(parsed.toString() == value) { "Workspace file page token must be a canonical UUID" }
            return WorkspaceFilePageToken(value)
        }

        fun random(): WorkspaceFilePageToken = WorkspaceFilePageToken(UUID.randomUUID().toString())
    }
}
