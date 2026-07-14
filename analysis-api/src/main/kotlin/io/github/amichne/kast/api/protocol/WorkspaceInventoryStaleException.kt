package io.github.amichne.kast.api.protocol

class WorkspaceInventoryStaleException(
    message: String = "Workspace inventory changed during paging",
) : AnalysisException(
    statusCode = 409,
    errorCode = "STALE_WORKSPACE_INVENTORY",
    message = message,
    retryable = true,
)
