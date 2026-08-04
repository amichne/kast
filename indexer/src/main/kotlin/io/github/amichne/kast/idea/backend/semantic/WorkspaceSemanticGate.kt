package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission

internal class WorkspaceSemanticGate(
    private val status: () -> IdeaIndexSemanticAdmission.Status,
    private val openRead: () -> IdeaIndexSemanticAdmission.WorkspaceReadToken,
    private val isReadCurrent: (IdeaIndexSemanticAdmission.WorkspaceReadToken) -> Boolean,
) {
    suspend fun <T> current(operation: suspend () -> T): T {
        val token = try {
            openRead()
        } catch (_: IllegalStateException) {
            throw conflict("Semantic operation started while the workspace was not READY")
        }
        val result = operation()
        if (!isReadCurrent(token)) {
            throw conflict("Workspace moved during the semantic operation; retry against the next READY generation")
        }
        return result
    }

    private fun conflict(message: String): ConflictException = ConflictException(
        message = message,
        details = mapOf("workspaceState" to status().toString()),
    )
}
