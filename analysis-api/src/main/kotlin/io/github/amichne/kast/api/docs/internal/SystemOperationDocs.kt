package io.github.amichne.kast.api.docs.internal

import io.github.amichne.kast.api.docs.OperationDoc

internal fun systemOperationDocs(): List<OperationDoc> = listOf(
        OperationDoc(
            operationId = "health",
            jsonRpcMethod = "health",
            summary = "Basic health check",
            tag = "system",
            responseSchema = "HealthResponse",
            description = "Returns a lightweight health check confirming the daemon " +
                "is responsive. Use this before dispatching heavier queries.",
        ),
        OperationDoc(
            operationId = "runtimeStatus",
            jsonRpcMethod = "runtime/status",
            summary = "Detailed runtime state including indexing progress",
            tag = "system",
            responseSchema = "RuntimeStatusResponse",
            description = "Returns the full runtime state including indexing progress, " +
                "backend identity, and workspace root. Use this to verify readiness " +
                "before running analysis commands.",
        ),
        OperationDoc(
            operationId = "runtimeOpenProject",
            jsonRpcMethod = "runtime/open-project",
            summary = "Open an authenticated exact-root project in this runtime host",
            tag = "system",
            requestSchema = "RuntimeOpenProjectRequest",
            responseSchema = "RuntimeOpenProjectResponse",
            description = "Consumes a local one-shot request and opens its canonical root " +
                "in this compatible IDEA application without replacing an existing project.",
            behavioralNotes = listOf(
                "The response is flushed before IDEA begins opening a new project frame.",
                "Requests are exact-root, one-shot, short-lived, and restricted to the selected local host.",
            ),
            errorCodes = listOf(
                "IDEA_OPEN_REQUEST_REJECTED",
                "IDEA_VERSION_UNSUPPORTED",
                "IDEA_PROJECT_OPEN_FAILED",
            ),
        ),
        OperationDoc(
            operationId = "runtimeShutdown",
            jsonRpcMethod = "runtime/shutdown",
            summary = "Request runtime host shutdown after the response is flushed",
            tag = "system",
            responseSchema = "RuntimeLifecycleResponse",
            description = "Requests that the runtime host shut down the current backend " +
                "after returning a JSON-RPC response. IDEA hosts stop the plugin backend " +
                "server and indexer without killing the IDE process; headless daemon " +
                "process lifecycle is handled by the top-level `kast stop` command.",
            behavioralNotes = listOf(
                "The response is flushed before the lifecycle action runs, so callers can observe an accepted request.",
                "Hosts without lifecycle support return a capability-not-supported JSON-RPC error.",
                "Prefer the top-level `kast stop` command for operator workflows; it handles stale descriptors and backend-specific cleanup.",
            ),
            errorCodes = listOf("CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "runtimeRestart",
            jsonRpcMethod = "runtime/restart",
            summary = "Request runtime host restart after the response is flushed",
            tag = "system",
            responseSchema = "RuntimeLifecycleResponse",
            description = "Requests that the runtime host rebuild the current backend " +
                "after returning a JSON-RPC response. IDEA hosts restart the plugin " +
                "backend server and indexer in the open IDE; headless daemon rebuilds " +
                "are handled by the top-level `kast restart` command.",
            behavioralNotes = listOf(
                "The response is flushed before the lifecycle action runs, so callers can observe an accepted request.",
                "Hosts without lifecycle support return a capability-not-supported JSON-RPC error.",
                "Prefer the top-level `kast restart` command for operator workflows; it combines the host lifecycle request with readiness waiting.",
            ),
            errorCodes = listOf("CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "capabilities",
            jsonRpcMethod = "capabilities",
            summary = "Advertised read and mutation capabilities",
            tag = "system",
            responseSchema = "BackendCapabilities",
            description = "Lists every read and mutation capability the current backend " +
                "advertises, along with server limits. Query this before calling an " +
                "operation to confirm it is available.",
        ),
)
