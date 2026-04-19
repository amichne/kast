package io.github.amichne.kast.api.protocol

open class AnalysisException(
    val statusCode: Int,
    val errorCode: String,
    override val message: String,
    val retryable: Boolean = false,
    val details: Map<String, String> = emptyMap(),
) : RuntimeException(message)

class ValidationException(
    message: String,
    details: Map<String, String> = emptyMap(),
) : AnalysisException(
    statusCode = 400,
    errorCode = "VALIDATION_ERROR",
    message = message,
    details = details,
)

class UnauthorizedException(
    message: String = "The provided authentication token is invalid",
) : AnalysisException(
    statusCode = 401,
    errorCode = "UNAUTHORIZED",
    message = message,
)

class NotFoundException(
    message: String,
    details: Map<String, String> = emptyMap(),
) : AnalysisException(
    statusCode = 404,
    errorCode = "NOT_FOUND",
    message = message,
    details = details,
)

class ConflictException(
    message: String,
    details: Map<String, String> = emptyMap(),
) : AnalysisException(
    statusCode = 409,
    errorCode = "CONFLICT",
    message = message,
    details = details,
)

class CapabilityNotSupportedException(
    capability: String,
    message: String,
) : AnalysisException(
    statusCode = 501,
    errorCode = "CAPABILITY_NOT_SUPPORTED",
    message = message,
    details = mapOf("capability" to capability),
)

class PartialApplyException(
    details: Map<String, String>,
    message: String = "One or more files failed during the commit phase",
) : AnalysisException(
    statusCode = 500,
    errorCode = "APPLY_PARTIAL_FAILURE",
    message = message,
    details = details,
)
