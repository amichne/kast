package io.github.amichne.kast.idea

import io.github.amichne.kast.api.continuation.ContinuationProjection
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFileCursorException
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFileCursorScope

internal data class IdeaWorkspaceFilePage(
    val files: List<String>,
    val nextOffset: Int,
    val hasMore: Boolean,
    val nextPageToken: String? = null,
) : ContinuationProjection() {
    companion object {
        fun from(
            module: IdeaWorkspaceModuleSnapshot,
            files: List<String>,
            offset: Int,
            pageSize: PositiveInt,
        ): IdeaWorkspaceFilePage {
            if (offset !in 0..files.size) {
                throw InvalidWorkspaceFileCursorException(InvalidWorkspaceFileCursorScope.PAGE_HANDLE)
            }
            val pageFiles = files.drop(offset).take(pageSize.value)
            val nextOffset = Math.addExact(offset, pageFiles.size)
            if (nextOffset == offset && nextOffset < files.size) {
                throw InvalidWorkspaceFileCursorException(
                    scope = InvalidWorkspaceFileCursorScope.PAGE_HANDLE,
                    message = "Workspace page cursor did not advance for module ${module.identity.value}",
                )
            }
            return IdeaWorkspaceFilePage(
                files = pageFiles,
                nextOffset = nextOffset,
                hasMore = nextOffset < files.size,
            )
        }
    }
}
