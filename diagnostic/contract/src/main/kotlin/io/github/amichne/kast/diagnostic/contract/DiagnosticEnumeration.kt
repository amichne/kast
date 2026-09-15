package io.github.amichne.kast.diagnostic.contract

import io.github.amichne.kast.kernel.ResourceBudget

/** Provider-owned detached traversal position. It is never proof of complete diagnostic scope. */
interface DiagnosticEnumerationCursor {
    val query: DiagnosticScopeQuery
    val retainedBytes: Long
}

sealed interface DiagnosticEnumerationRequest {
    val query: DiagnosticScopeQuery

    data class First(override val query: DiagnosticScopeQuery) : DiagnosticEnumerationRequest

    data class Resume(val cursor: DiagnosticEnumerationCursor) : DiagnosticEnumerationRequest {
        override val query: DiagnosticScopeQuery
            get() = cursor.query
    }
}

enum class DiagnosticEnumerationStop {
    WORK_LIMIT,
    TIME_LIMIT,
    FILE_LIMIT,
}

/** Only Exhausted proves that no enumeration obligations remain; an empty page proves no absence. */
sealed interface DiagnosticEnumerationResult {
    val files: List<DiagnosticSourceFile>

    data class Advancing(
        override val files: List<DiagnosticSourceFile>,
        val cursor: DiagnosticEnumerationCursor,
        val reason: DiagnosticEnumerationStop,
    ) : DiagnosticEnumerationResult

    data class Exhausted(override val files: List<DiagnosticSourceFile>) : DiagnosticEnumerationResult

    data class Rejected(val reason: DiagnosticScopeResolutionFailure) : DiagnosticEnumerationResult {
        override val files: List<DiagnosticSourceFile> = emptyList()
    }
}

/** Each call releases all platform objects. The caller owns same-basis admission and final validation. */
fun interface DiagnosticScopeEnumerator {
    suspend fun enumerate(request: DiagnosticEnumerationRequest, budget: ResourceBudget): DiagnosticEnumerationResult
}
