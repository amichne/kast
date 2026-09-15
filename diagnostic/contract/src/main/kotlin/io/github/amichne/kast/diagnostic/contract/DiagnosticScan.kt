package io.github.amichne.kast.diagnostic.contract

import io.github.amichne.kast.kernel.ResourceBudget

/** Detached diagnostic-owned progress. It cannot satisfy DiagnosticCompleteCoverage or mutation verification. */
interface DiagnosticScanCheckpoint {
    val query: DiagnosticScopeQuery
    val retainedBytes: Long
}

sealed interface DiagnosticScanRequest {
    val query: DiagnosticScopeQuery

    data class First(override val query: DiagnosticScopeQuery) : DiagnosticScanRequest

    data class Resume(val checkpoint: DiagnosticScanCheckpoint) : DiagnosticScanRequest {
        override val query: DiagnosticScopeQuery
            get() = checkpoint.query
    }
}

/** Inventory cardinality is unknown until enumeration is exhausted. */
sealed interface DiagnosticScanInventory {
    data object Enumerating : DiagnosticScanInventory

    data class Exhausted(val files: List<DiagnosticSourceFile>) : DiagnosticScanInventory
}

/** Cumulative evidence for the original basis; page facts retain each complete one-file scope. */
data class DiagnosticScanPage(
    val facts: List<DiagnosticFact>,
    val analyzedFiles: List<DiagnosticSourceFile>,
    val limitations: Set<DiagnosticLimitation>,
    val inventory: DiagnosticScanInventory,
)

sealed interface DiagnosticScanStop {
    data class Enumeration(val reason: DiagnosticEnumerationStop) : DiagnosticScanStop

    data object AnalysisPending : DiagnosticScanStop

    data object OutputPending : DiagnosticScanStop
}

sealed interface DiagnosticScanRejection {
    data object StaleBasis : DiagnosticScanRejection

    data class Scope(val reason: DiagnosticScopeResolutionFailure) : DiagnosticScanRejection

    data class Compiler(val reason: DiagnosticReadRejection) : DiagnosticScanRejection

    data object IndivisibleUnitExceedsBudget : DiagnosticScanRejection
}

sealed interface DiagnosticScanResult {
    data class Advancing(
        val page: DiagnosticScanPage,
        val checkpoint: DiagnosticScanCheckpoint,
        val stop: DiagnosticScanStop,
    ) : DiagnosticScanResult

    data class Complete(val page: DiagnosticScanPage) : DiagnosticScanResult

    data class Qualified(val page: DiagnosticScanPage) : DiagnosticScanResult

    data class Rejected(val reason: DiagnosticScanRejection) : DiagnosticScanResult
}

fun interface DiagnosticScanOperations {
    suspend fun scan(request: DiagnosticScanRequest, budget: ResourceBudget): DiagnosticScanResult
}
