package io.github.amichne.kast.server

import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.AnalysisException
import io.github.amichne.kast.api.ApiErrorResponse
import io.github.amichne.kast.api.CapabilityNotSupportedException
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.PageInfo
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ReferencesResult
import io.github.amichne.kast.api.ValidationException
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

private suspend fun execute(
    config: AnalysisServerConfig,
    block: suspend () -> Any,
): Any = withTimeout(config.requestTimeoutMillis.milliseconds) {
    block()
}

private fun ApplicationCall.authorize(config: AnalysisServerConfig) {
    val expectedToken = config.token ?: return
    val providedToken = request.headers["X-Kast-Token"]
    if (providedToken != expectedToken) {
        throw io.github.amichne.kast.api.UnauthorizedException()
    }
}

private suspend fun requireReadCapability(
    backend: AnalysisBackend,
    capability: ReadCapability,
) {
    val capabilities = backend.capabilities()
    if (!capabilities.readCapabilities.contains(capability)) {
        throw CapabilityNotSupportedException(
            capability = capability.name,
            message = "The backend does not advertise $capability",
        )
    }
}

private suspend fun requireMutationCapability(
    backend: AnalysisBackend,
    capability: MutationCapability,
) {
    val capabilities = backend.capabilities()
    if (!capabilities.mutationCapabilities.contains(capability)) {
        throw CapabilityNotSupportedException(
            capability = capability.name,
            message = "The backend does not advertise $capability",
        )
    }
}

private fun ReferencesResult.withLimit(limit: Int): ReferencesResult {
    if (references.size <= limit) {
        return this
    }

    return copy(
        references = references.take(limit),
        page = PageInfo(
            truncated = true,
            nextPageToken = references[limit - 1].startOffset.toString(),
        ),
    )
}

private fun DiagnosticsResult.withLimit(limit: Int): DiagnosticsResult {
    if (diagnostics.size <= limit) {
        return this
    }

    return copy(
        diagnostics = diagnostics.take(limit),
        page = PageInfo(
            truncated = true,
            nextPageToken = diagnostics[limit - 1].location.startOffset.toString(),
        ),
    )
}

private fun AnalysisException.toErrorResponse(requestId: String): ApiErrorResponse = ApiErrorResponse(
    requestId = requestId,
    code = errorCode,
    message = message,
    retryable = retryable,
    details = details,
)

private fun requestId(call: ApplicationCall): String {
    return call.request.headers["X-Request-Id"] ?: UUID.randomUUID().toString()
}

private fun validateFilePosition(
    filePath: String,
    offset: Int,
) {
    validateAbsoluteFilePath(filePath)
    if (offset < 0) {
        throw ValidationException("Offsets must be greater than or equal to zero")
    }
}

private fun validateAbsoluteFilePath(filePath: String) {
    val path = java.nio.file.Path.of(filePath)
    if (!path.isAbsolute) {
        throw ValidationException(
            message = "File paths must be absolute",
            details = mapOf("filePath" to filePath),
        )
    }
}
