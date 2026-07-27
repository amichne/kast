package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.docs.DocField
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class WorkspaceFilesPublicPageToken private constructor(
    @DocField(description = "Canonical opaque UUID handle for one public workspace-files continuation.")
    val value: String,
) {
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
