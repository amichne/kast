package io.github.amichne.kast.api.docs.internal

import io.github.amichne.kast.api.docs.OperationDoc

internal fun mutationOperationDocs(): List<OperationDoc> = listOf(
        OperationDoc(
            operationId = "rename",
            jsonRpcMethod = "raw/rename",
            summary = "Plan a symbol rename (dry-run by default)",
            tag = "mutation",
            capability = "RENAME",
            requestSchema = "RenameQuery",
            responseSchema = "RenameResult",
            description = "Plans a symbol rename by computing all text edits needed " +
                "across the workspace. This is a dry-run by default — it returns " +
                "edits without applying them.",
            behavioralNotes = listOf(
                "The result includes file hashes for conflict detection when " +
                    "applying edits later.",
                "Pair with `raw/apply-edits` to execute the rename after review.",
            ),
            errorCodes = listOf("NOT_FOUND"),
        ),
        OperationDoc(
            operationId = "optimizeImports",
            jsonRpcMethod = "raw/optimize-imports",
            summary = "Optimize imports for one or more files",
            tag = "mutation",
            capability = "OPTIMIZE_IMPORTS",
            requestSchema = "ImportOptimizeQuery",
            responseSchema = "ImportOptimizeResult",
            description = "Optimizes imports for one or more files, removing unused " +
                "imports and sorting the remainder.",
            behavioralNotes = listOf(
                "Returns the computed edits and file hashes. The daemon applies " +
                    "changes directly.",
            ),
            errorCodes = listOf("NOT_FOUND", "CAPABILITY_NOT_SUPPORTED"),
        ),
        OperationDoc(
            operationId = "applyEdits",
            jsonRpcMethod = "raw/apply-edits",
            summary = "Apply a prepared edit plan with conflict detection",
            tag = "mutation",
            capability = "APPLY_EDITS",
            requestSchema = "ApplyEditsQuery",
            responseSchema = "ApplyEditsResult",
            description = "Applies a prepared edit plan with file-hash conflict " +
                "detection. Pass the edits and hashes returned by a prior " +
                "`raw/rename` or other planning operation.",
            behavioralNotes = listOf(
                "File hashes are compared before writing. If a file changed since " +
                    "the edits were planned, the operation fails with a conflict error.",
                "Supports optional `fileOperations` for creating or deleting files.",
            ),
            errorCodes = listOf("CONFLICT", "VALIDATION_ERROR"),
        ),
        OperationDoc(
            operationId = "refreshWorkspace",
            jsonRpcMethod = "raw/workspace-refresh",
            summary = "Force a targeted or full workspace state refresh",
            tag = "mutation",
            capability = "REFRESH_WORKSPACE",
            requestSchema = "RefreshQuery",
            responseSchema = "RefreshResult",
            description = "Refreshes the daemon after external file modifications. " +
                "A successful focused refresh admits each requested Kotlin path and " +
                "refreshes its durable relationships. The result returns current file-local " +
                "relationship failures that the caller can externalize.",
            behavioralNotes = listOf(
                "Pass specific file paths for a targeted refresh, or omit for a " +
                    "full workspace refresh.",
                "Each focused path separately reports filesystem discovery, source-module " +
                    "ownership, index admission, and analysis availability.",
                "Compiler diagnostics remain data and do not block relationship indexing.",
                "Eligible file-local relationship failures carry an ID, path, and code for externalization.",
                "Pending admission is retried for a bounded interval. The result reports " +
                    "attempt and elapsed-time progress and fails closed if admission remains incomplete.",
                "Removed paths are terminal refresh results and do not count as skipped analysis.",
            ),
            errorCodes = listOf("CAPABILITY_NOT_SUPPORTED"),
        ),
)
