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
