package io.github.amichne.kast.api.protocol

class InvalidWorkspaceFileCursorException(
    val scope: InvalidWorkspaceFileCursorScope,
    message: String = "Invalid workspace file cursor",
) : AnalysisException(
    statusCode = 400,
    errorCode = "INVALID_WORKSPACE_FILE_CURSOR",
    message = message,
    details = mapOf("scope" to scope.name),
)
