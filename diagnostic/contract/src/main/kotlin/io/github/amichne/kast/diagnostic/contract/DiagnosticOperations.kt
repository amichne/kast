package io.github.amichne.kast.diagnostic.contract

data class DiagnosticCheckRequest(
    val scope: DiagnosticScope,
)

enum class DiagnosticReadRejection {
    WORKSPACE_NOT_READY,
    WORKSPACE_ROOT_MISMATCH,
    STALE_GENERATION,
    WORKSPACE_INDEX_UNAVAILABLE,
    SCOPE_REJECTED,
    COMPILER_CONTRACT_VIOLATION,
}

sealed interface DiagnosticCheckResult {
    data class Complete(
        val batch: DiagnosticBatch,
        val coverage: DiagnosticCompleteCoverage,
    ) : DiagnosticCheckResult

    data class Qualified(
        val batch: DiagnosticBatch,
        val coverage: DiagnosticIncompleteCoverage,
    ) : DiagnosticCheckResult

    data class Rejected(
        val reason: DiagnosticReadRejection,
    ) : DiagnosticCheckResult
}

/** Public operation contract for `diagnostic.check`. */
fun interface DiagnosticOperations {
    /**
     * Proof transition: `DiagnosticCheckRequest -> DiagnosticCheckResult`.
     *
     * Complete or qualified output establishes detached compiler diagnostics for the request's
     * exact scope and semantic generation. [DiagnosticReadRejection] is the closed expected
     * failure. Raw compiler and workspace observation remain behind service ports.
     */
    suspend fun check(request: DiagnosticCheckRequest): DiagnosticCheckResult
}
