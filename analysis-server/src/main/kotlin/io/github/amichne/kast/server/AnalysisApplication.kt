package io.github.amichne.kast.server

import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.AnalysisException
import io.github.amichne.kast.api.ApiErrorResponse
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CapabilityNotSupportedException
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.PageInfo
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.RefreshQuery
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.ReferencesResult
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.ValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.UUID

fun Application.kastModule(
    backend: AnalysisBackend,
    config: AnalysisServerConfig,
) {
    val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    install(ContentNegotiation) {
        json(json)
    }

    install(StatusPages) {
        exception<AnalysisException> { call, cause ->
            call.respond(
                status = HttpStatusCode.fromValue(cause.statusCode),
                message = cause.toErrorResponse(requestId(call)),
            )
        }

        exception<Throwable> { call, cause ->
            call.respond(
                status = HttpStatusCode.InternalServerError,
                message = ApiErrorResponse(
                    requestId = requestId(call),
                    code = "INTERNAL_ERROR",
                    message = cause.message ?: cause::class.java.simpleName,
                    retryable = false,
                ),
            )
        }
    }

    routing {
        route("/api/v1") {
            get("/health") {
                val response = execute(config) {
                    backend.health()
                }
                call.respond(response)
            }

            get("/runtime/status") {
                val response = execute(config) {
                    backend.runtimeStatus()
                }
                call.respond(response)
            }

            get("/capabilities") {
                val response = execute(config) {
                    backend.capabilities()
                }
                call.respond(response)
            }

            post("/symbol/resolve") {
                call.authorize(config)
                requireReadCapability(backend, ReadCapability.RESOLVE_SYMBOL)
                val query = call.receive<SymbolQuery>()
                validateFilePosition(query.position.filePath, query.position.offset)
                val response = execute(config) {
                    backend.resolveSymbol(query)
                }
                call.respond(response)
            }

            post("/references") {
                call.authorize(config)
                requireReadCapability(backend, ReadCapability.FIND_REFERENCES)
                val query = call.receive<ReferencesQuery>()
                validateFilePosition(query.position.filePath, query.position.offset)
                val response = execute(config) {
                    backend.findReferences(query).withLimit(config.maxResults)
                }
                call.respond(response)
            }

            post("/call-hierarchy") {
                call.authorize(config)
                requireReadCapability(backend, ReadCapability.CALL_HIERARCHY)
                val query = call.receive<CallHierarchyQuery>()
                validateFilePosition(query.position.filePath, query.position.offset)
                if (query.depth < 0) {
                    throw ValidationException("Call hierarchy depth must be greater than or equal to zero")
                }
                if (query.maxTotalCalls < 1) {
                    throw ValidationException("Call hierarchy maxTotalCalls must be greater than zero")
                }
                if (query.maxChildrenPerNode < 1) {
                    throw ValidationException("Call hierarchy maxChildrenPerNode must be greater than zero")
                }
                val timeoutMillis = query.timeoutMillis
                if (timeoutMillis != null && timeoutMillis < 1) {
                    throw ValidationException("Call hierarchy timeoutMillis must be greater than zero when provided")
                }
                val response = execute(config) {
                    backend.callHierarchy(query)
                }
                call.respond(response)
            }

            post("/diagnostics") {
                call.authorize(config)
                requireReadCapability(backend, ReadCapability.DIAGNOSTICS)
                val query = call.receive<DiagnosticsQuery>()
                if (query.filePaths.isEmpty()) {
                    throw ValidationException("At least one file path is required for diagnostics")
                }
                query.filePaths.forEach { validateAbsoluteFilePath(it) }
                val response = execute(config) {
                    backend.diagnostics(query).withLimit(config.maxResults)
                }
                call.respond(response)
            }

            post("/rename") {
                call.authorize(config)
                requireMutationCapability(backend, MutationCapability.RENAME)
                val query = call.receive<RenameQuery>()
                validateFilePosition(query.position.filePath, query.position.offset)
                if (query.newName.isBlank()) {
                    throw ValidationException("The new symbol name must not be blank")
                }
                val response = execute(config) {
                    backend.rename(query)
                }
                call.respond(response)
            }

            post("/edits/apply") {
                call.authorize(config)
                requireMutationCapability(backend, MutationCapability.APPLY_EDITS)
                val query = call.receive<ApplyEditsQuery>()
                val response = execute(config) {
                    backend.applyEdits(query)
                }
                call.respond(response)
            }

            post("/workspace/refresh") {
                call.authorize(config)
                requireMutationCapability(backend, MutationCapability.REFRESH_WORKSPACE)
                val query = call.receive<RefreshQuery>()
                query.filePaths.forEach(::validateAbsoluteFilePath)
                val response = execute(config) {
                    backend.refresh(query)
                }
                call.respond(response)
            }
        }

        get("/") {
            call.respondText("kast")
        }
    }
}

private suspend fun execute(
    config: AnalysisServerConfig,
    block: suspend () -> Any,
): Any = withTimeout(config.requestTimeoutMillis) {
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
