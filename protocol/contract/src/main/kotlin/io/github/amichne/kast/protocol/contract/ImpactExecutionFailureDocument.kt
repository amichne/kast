@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactExecutionFailureDocument {
    @Serializable @SerialName("PRESENTATION_ONLY") data object PresentationOnly : ImpactExecutionFailureDocument

    @Serializable
    @SerialName("PATH")
    data class Path(val cause: ImpactExecutionPathCause) : ImpactExecutionFailureDocument

    @Serializable
    @SerialName("LEDGER")
    data class Ledger(val cause: ImpactExecutionLedgerCause) : ImpactExecutionFailureDocument

    @Serializable
    @SerialName("ACCOUNTING")
    data class Accounting(val cause: ImpactExecutionAccountingCause) : ImpactExecutionFailureDocument

    @Serializable
    @SerialName("REPRESENTATION")
    data class Representation(val cause: ImpactExecutionRepresentationCause) : ImpactExecutionFailureDocument

    @Serializable
    @SerialName("BOUNDARY")
    data class Boundary(val cause: ImpactExecutionBoundaryCause) : ImpactExecutionFailureDocument

    @Serializable
    @SerialName("MODEL_HISTORY")
    data class ModelHistory(val cause: ImpactExecutionModelHistoryCause) : ImpactExecutionFailureDocument

    @Serializable
    @SerialName("SELECTION")
    data class Selection(val cause: ImpactExecutionSelectionCause) : ImpactExecutionFailureDocument

    @Serializable
    @SerialName("ROW_IDENTITY")
    data class RowIdentity(val cause: ImpactExecutionRowIdentityCause) : ImpactExecutionFailureDocument
}

@Serializable
enum class ImpactExecutionPathCause {
    DISCONNECTED_STEP,
    TERMINAL_SITE_MISMATCH,
    REPRESENTATION_SITE_MISMATCH,
    REPRESENTATION_PATH_MISMATCH,
    CONSUMER_REPRESENTATION_MISMATCH,
    EXCLUSION_UNPROVEN,
    TERMINAL_UNPROVEN,
}

@Serializable
enum class ImpactExecutionLedgerCause {
    EMPTY_SEEDS,
    DUPLICATE_SEED,
    DUPLICATE_PATH,
    FOREIGN_PRODUCER,
    MISSING_SEED_PATH,
    DOMAIN_MISMATCH,
    CONFLICTING_OBSERVATIONS,
    MISSING_NATIVE_OBSERVATION,
    UNPROVEN_COMPILER_STEP,
    MISSING_BRANCH,
    MISSING_OBLIGATION,
    UNDECLARED_MODEL,
    DUPLICATE_MODEL_REFERENCE,
    UNPROVEN_TERMINAL,
    UNACCOUNTED_NATIVE_READ,
    PRODUCER_IDENTITY_MISMATCH,
    DUPLICATE_REQUESTED_SITE,
    FOREIGN_REQUESTED_SITE,
}

@Serializable
enum class ImpactExecutionAccountingCause {
    DUPLICATE_PATH
}

@Serializable
enum class ImpactExecutionRepresentationCause {
    SITE_MISMATCH,
    BASIS_MISMATCH,
    CALLABLE_MISMATCH,
    POSITION_MISMATCH,
    EMPTY_MERGE,
}

@Serializable
enum class ImpactExecutionBoundaryCause {
    SOURCE_MISMATCH,
    TARGET_MISMATCH,
    BASIS_MISMATCH,
    KIND_MISMATCH,
}

@Serializable
enum class ImpactExecutionModelHistoryCause {
    MISSING_APPLICATION
}

@Serializable
enum class ImpactExecutionSelectionCause {
    EXECUTION_REJECTED,
    PRESENTATION_ONLY_ROWS,
    BASIS_MISMATCH,
    INCONSISTENT_COVERAGE,
    UNKNOWN_ROW,
    DUPLICATE_ROW,
}

@Serializable
enum class ImpactExecutionRowIdentityCause {
    CHANGED_RETAINED_ROWS
}
