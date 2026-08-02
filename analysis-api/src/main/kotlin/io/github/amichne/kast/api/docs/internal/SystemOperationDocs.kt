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
                "indexer identity, and workspace root. Use this to verify readiness " +
                "before running analysis commands.",
        ),
        OperationDoc(
            operationId = "runtimeShutdown",
            jsonRpcMethod = "runtime/shutdown",
            summary = "Request runtime host shutdown after the response is flushed",
            tag = "system",
            responseSchema = "RuntimeLifecycleResponse",
            description = "Requests that the runtime host shut down the current indexer " +
                "after returning a JSON-RPC response. The top-level `kast stop` command " +
                "also handles stale endpoint state.",
            behavioralNotes = listOf(
                "The response is flushed before the lifecycle action runs, so callers can observe an accepted request.",
                "Hosts without lifecycle support return a capability-not-supported JSON-RPC error.",
                "Prefer the top-level `kast stop` command for operator workflows; it handles stale descriptors and cleanup.",
            ),
            errorCodes = listOf("CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "runtimeRestart",
            jsonRpcMethod = "runtime/restart",
            summary = "Request runtime host restart after the response is flushed",
            tag = "system",
            responseSchema = "RuntimeLifecycleResponse",
            description = "Requests that the runtime host restart the current indexer " +
                "after returning a JSON-RPC response. The top-level `kast restart` " +
                "command also waits for readiness.",
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
            description = "Lists every read and mutation capability the current indexer " +
                "advertises, along with server limits. Query this before calling an " +
                "operation to confirm it is available.",
        ),
)
