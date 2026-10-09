package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Each completion failure owns exactly the proof detail its case requires. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface QueryCompletionCauseDocument {
    val reason: QueryCompletionUnprovenReason

    @Serializable
    @SerialName("INCOMPLETE_EXECUTION")
    data object IncompleteExecution : QueryCompletionCauseDocument {
        override val reason
            get() = QueryCompletionUnprovenReason.INCOMPLETE_EXECUTION
    }

    @Serializable
    @SerialName("ITEM_FAILURE")
    data object ItemFailure : QueryCompletionCauseDocument {
        override val reason
            get() = QueryCompletionUnprovenReason.ITEM_FAILURE
    }

    @Serializable
    @SerialName("OMITTED_EVIDENCE")
    data object OmittedEvidence : QueryCompletionCauseDocument {
        override val reason
            get() = QueryCompletionUnprovenReason.OMITTED_EVIDENCE
    }

    @Serializable
    @SerialName("CALLBACK_GRAPH_UNPROVEN")
    data class CallbackGraphUnproven(val graphFailure: QueryCallbackGraphFailureDocument) :
        QueryCompletionCauseDocument {
        override val reason
            get() = QueryCompletionUnprovenReason.CALLBACK_GRAPH_UNPROVEN
    }

    @Serializable
    @SerialName("INVESTIGATION_UNPROVEN")
    data class InvestigationUnproven(val investigationFailure: QueryInvestigationCompletionFailureDocument) :
        QueryCompletionCauseDocument {
        override val reason
            get() = QueryCompletionUnprovenReason.INVESTIGATION_UNPROVEN
    }

    @Serializable
    @SerialName("RETENTION_UNAVAILABLE")
    data class RetentionUnavailable(val failure: QueryCompletionRetentionFailure) : QueryCompletionCauseDocument {
        override val reason
            get() = QueryCompletionUnprovenReason.RETENTION_UNAVAILABLE
    }
}
