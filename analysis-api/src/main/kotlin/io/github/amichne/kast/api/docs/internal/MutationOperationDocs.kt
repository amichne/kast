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
        operationId = "planReplacement",
        jsonRpcMethod = "raw/plan-replacement",
        summary = "Plan an identity-preserving function-body replacement",
        tag = "mutation",
        capability = "PLAN_REPLACEMENT",
        requestSchema = "ReplacementPlanQuery",
        responseSchema = "ReplacementPlanResult",
        description = "Plans one Kotlin function-body replacement without writing. " +
                      "The result binds the exact compiler identity, declaration signature, references, and file images.",
        behavioralNotes = listOf(
            "Only a compiler-proven function with an unchanged observable signature is supported.",
            "A limited or inconsistent proof fails before any source write.",
        ),
        errorCodes = listOf("NOT_FOUND", "REPLACEMENT_PROOF_INCOMPLETE"),
    ),
    OperationDoc(
        operationId = "planAddFile",
        jsonRpcMethod = "raw/plan-add-file",
        summary = "Plan a compiler-proven Kotlin source file addition",
        tag = "mutation",
        capability = "PLAN_ADD_FILE",
        requestSchema = "AddFilePlanQuery",
        responseSchema = "AddFilePlanResult",
        description = "Plans one Kotlin source file addition without writing. " +
                      "The result proves source ownership, target absence, declarations, bindings, and the exact postimage.",
        behavioralNotes = listOf(
            "The target must belong to one proven Kotlin source root and must not exist.",
            "Collision or rebinding uncertainty fails before any source write.",
        ),
        errorCodes = listOf("ADDITION_PROOF_INCOMPLETE", "CONFLICT"),
    ),
    OperationDoc(
        operationId = "verifyMutationPostcondition",
        jsonRpcMethod = "raw/verify-mutation-postcondition",
        summary = "Verify one exact mutation postcondition",
        tag = "mutation",
        capability = "VERIFY_MUTATION_POSTCONDITION",
        requestSchema = "MutationPostconditionQuery",
        responseSchema = "MutationPostconditionResult",
        description = "Verifies the exact postimages and compiler evidence for one rename, replacement, " +
                      "file addition, or declaration addition authority.",
        behavioralNotes = listOf(
            "The authority is a closed operation-specific proof retained from planning.",
            "Only VERIFIED with matching exact postimages is a successful result.",
        ),
        errorCodes = listOf("MUTATION_POSTCONDITION_FAILED", "CONFLICT"),
    ),
    OperationDoc(
        operationId = "exactFileObservation",
        jsonRpcMethod = "raw/exact-file-observation",
        summary = "Observe one file as an exact byte image or proven absence",
        tag = "mutation",
        capability = "EXACT_FILE_OBSERVATION",
        requestSchema = "RawExactFileObservationQuery",
        responseSchema = "RawExactFileObservationResult",
        description = "Observes one canonical workspace-relative file through the secure exact-root boundary.",
        behavioralNotes = listOf(
            "The closed result is ABSENT or PRESENT with canonical Base64 and lowercase SHA-256 evidence.",
            "A mutation attempt identifier applies the active backend fence.",
        ),
        errorCodes = listOf("VALIDATION_ERROR", "CONFLICT"),
    ),
    OperationDoc(
        operationId = "exactFileImageCas",
        jsonRpcMethod = "raw/exact-file-image-cas",
        summary = "Commit one exact file byte image with compare-and-swap",
        tag = "mutation",
        capability = "EXACT_FILE_IMAGE_CAS",
        requestSchema = "ExactFileImageQuery",
        responseSchema = "ExactFileImageResult",
        description = "Replaces one exact file image only when its current SHA-256 and mutation authority match.",
        behavioralNotes = listOf(
            "Verified mutation requests supply a predeclared scratch set owned by the durable journal.",
            "A hash conflict or unsafe retained file state fails without reporting a commit.",
        ),
        errorCodes = listOf("VALIDATION_ERROR", "CONFLICT"),
    ),
    OperationDoc(
        operationId = "inspectMutationScratch",
        jsonRpcMethod = "raw/inspect-mutation-scratch",
        summary = "Fence a mutation attempt and inspect its exact scratch namespace",
        tag = "mutation",
        capability = "MUTATION_SCRATCH_RECOVERY",
        requestSchema = "MutationScratchInspectQuery",
        responseSchema = "MutationScratchInspectResult",
        description = "Admits one active mutation attempt and inspects only its journal-declared scratch sets " +
                      "and Kast-prefixed entries in the supplied target parents.",
        behavioralNotes = listOf(
            "Owned observations bind exact role paths; unowned Kast-prefixed entries remain explicit blockers.",
            "The result is strictly ordered and does not infer absence outside the inspected parents.",
        ),
        errorCodes = listOf("VALIDATION_ERROR", "CONFLICT"),
    ),
    OperationDoc(
        operationId = "recoverMutationScratch",
        jsonRpcMethod = "raw/recover-mutation-scratch",
        summary = "Restore or finalize one journal-owned mutation scratch set",
        tag = "mutation",
        capability = "MUTATION_SCRATCH_RECOVERY",
        requestSchema = "MutationScratchRecoveryQuery",
        responseSchema = "MutationScratchRecoveryResult",
        description = "Restores the exact preimage or finalizes the exact postimage using only one supplied " +
                      "journal-owned scratch set under the active mutation fence.",
        behavioralNotes = listOf(
            "scratchDirection defines which exact image each scratch role can contain.",
            "Success proves the selected target state and all four supplied scratch roles absent.",
        ),
        errorCodes = listOf("VALIDATION_ERROR", "CONFLICT"),
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
