package io.github.amichne.kast.api.protocol

class WorkspaceProjectModelIncompleteException(
    val reason: WorkspaceProjectModelIncompleteReason,
    message: String = "Workspace project model is incomplete",
) : AnalysisException(
    statusCode = 503,
    errorCode = "WORKSPACE_PROJECT_MODEL_INCOMPLETE",
    message = message,
    retryable = true,
    details = mapOf("reason" to reason.name),
)
