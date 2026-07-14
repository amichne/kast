package io.github.amichne.kast.api.validation

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class WorkspaceFilesPublicPageToken private constructor(val value: String) {
    init {
        val parsed = UUID.fromString(value)
        require(parsed.toString() == value) { "Workspace-files public page token must be a canonical UUID" }
    }

    companion object {
        fun parse(value: String): WorkspaceFilesPublicPageToken = WorkspaceFilesPublicPageToken(value)

        fun random(): WorkspaceFilesPublicPageToken =
            WorkspaceFilesPublicPageToken(UUID.randomUUID().toString())
    }
}
