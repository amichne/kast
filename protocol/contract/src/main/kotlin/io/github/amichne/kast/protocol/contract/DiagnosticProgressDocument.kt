package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** No numeric total is asserted while the original source inventory is still being enumerated. */
@Serializable
sealed interface DiagnosticInventoryDocument {
    @Serializable @SerialName("enumerating") data object Enumerating : DiagnosticInventoryDocument

    @Serializable
    @SerialName("exhausted")
    data class Exhausted(val totalFiles: ProtocolCount) : DiagnosticInventoryDocument
}

@Serializable
enum class DiagnosticProgressStage {
    @SerialName("enumeration") ENUMERATION,
    @SerialName("analysis") ANALYSIS,
    @SerialName("output") OUTPUT,
    @SerialName("finished") FINISHED,
}

@Serializable
enum class DiagnosticProgressStop {
    @SerialName("enumeration_work_limit") ENUMERATION_WORK_LIMIT,
    @SerialName("enumeration_time_limit") ENUMERATION_TIME_LIMIT,
    @SerialName("enumeration_file_limit") ENUMERATION_FILE_LIMIT,
    @SerialName("analysis_pending") ANALYSIS_PENDING,
    @SerialName("output_pending") OUTPUT_PENDING,
    @SerialName("finished") FINISHED,
}

/** Cumulative coverage and stage belong to the original unchanged basis. */
@Serializable
data class DiagnosticProgressDocument(
    val stage: DiagnosticProgressStage,
    val inventory: DiagnosticInventoryDocument,
    val analyzedFiles: List<ProtocolText>,
    @SerialName("execution_budget") val executionBudget: ExecutionBudgetReport? = null,
    val stop: DiagnosticProgressStop,
    val knownDiagnosticCount: DiagnosticKnownCountDocument,
    val requestedPath: ProtocolText? = null,
)
