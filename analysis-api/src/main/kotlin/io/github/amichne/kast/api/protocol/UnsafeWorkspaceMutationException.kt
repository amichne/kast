package io.github.amichne.kast.api.protocol

/**
 * A workspace mutation could not preserve its filesystem containment guarantee.
 */
class UnsafeWorkspaceMutationException(
    message: String,
    details: Map<String, String>,
) : AnalysisException(
    statusCode = 409,
    errorCode = "UNSAFE_WORKSPACE_MUTATION",
    message = message,
    details = details,
)
