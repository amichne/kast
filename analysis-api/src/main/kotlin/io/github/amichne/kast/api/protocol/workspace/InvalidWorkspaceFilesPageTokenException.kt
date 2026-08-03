package io.github.amichne.kast.api.protocol

class InvalidWorkspaceFilesPageTokenException(
    message: String = "Invalid workspace-files public page token",
) : AnalysisException(
    statusCode = 400,
    errorCode = "INVALID_WORKSPACE_FILES_PAGE_TOKEN",
    message = message,
)
