package io.github.amichne.kast.api.validation

import java.util.UUID

@JvmInline
value class WorkspaceFileSnapshotToken private constructor(val value: String) {
    companion object {
        fun parse(value: String): WorkspaceFileSnapshotToken {
            val parsed = UUID.fromString(value)
            require(parsed.toString() == value) { "Workspace file snapshot token must be a canonical UUID" }
            return WorkspaceFileSnapshotToken(value)
        }

        fun random(): WorkspaceFileSnapshotToken = WorkspaceFileSnapshotToken(UUID.randomUUID().toString())
    }
}
