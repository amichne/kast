package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.api.protocol.ConflictException

internal class WorkspaceSemanticGate(
    private val readAuthority: WorkspaceSemanticReadAuthority,
) {
    suspend fun <T> current(operation: suspend () -> T): T {
        val token = try {
            readAuthority.openRead()
        } catch (_: IllegalStateException) {
            throw conflict("Semantic operation started while the workspace was not READY")
        }
        try {
            val result = operation()
            if (!readAuthority.isReadCurrent(token)) {
                throw conflict("Workspace moved during the semantic operation; retry against the next READY generation")
            }
            return result
        } finally {
            token.close()
        }
    }

    private fun conflict(message: String): ConflictException = ConflictException(
        message = message,
        details = mapOf("workspaceState" to readAuthority.status().toString()),
    )
}
