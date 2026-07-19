package io.github.amichne.kast.api.contract.compatibility

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class WorkspaceMetadataRevision(
    @DocField(description = "Positive revision of the exact-workspace-root metadata document.")
    val value: Int,
) {
    init {
        require(value > 0) { "Workspace metadata revision must be positive" }
    }

    companion object {
        val CURRENT = WorkspaceMetadataRevision(3)
    }
}
