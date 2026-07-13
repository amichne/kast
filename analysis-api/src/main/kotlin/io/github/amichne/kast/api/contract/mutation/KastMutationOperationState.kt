package io.github.amichne.kast.api.contract.mutation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface KastMutationOperationState {
    val trace: KastMutationExecutionTrace
    val cancellationRequested: Boolean

    @Serializable
    @SerialName("QUEUED")
    data class Queued(
        override val trace: KastMutationExecutionTrace = KastMutationExecutionTrace(),
        override val cancellationRequested: Boolean = false,
    ) : KastMutationOperationState

    @Serializable
    @SerialName("APPLYING")
    data class Applying(
        val stage: KastMutationProgressStage,
        override val trace: KastMutationExecutionTrace,
        override val cancellationRequested: Boolean,
    ) : KastMutationOperationState {
        init {
            require(stage in APPLYING_STAGES) { "$stage is not an applying stage" }
            require(trace.currentStage == stage) { "Applying stage must match the trace current stage" }
        }
    }

    @Serializable
    @SerialName("VALIDATING")
    data class Validating(
        val stage: KastMutationProgressStage,
        override val trace: KastMutationExecutionTrace,
        override val cancellationRequested: Boolean,
    ) : KastMutationOperationState {
        init {
            require(stage in VALIDATING_STAGES) { "$stage is not a validating stage" }
            require(trace.currentStage == stage) { "Validating stage must match the trace current stage" }
        }
    }

    @Serializable
    @SerialName("COMPLETED")
    data class Completed(
        val result: KastSemanticMutationResult,
        override val trace: KastMutationExecutionTrace,
        override val cancellationRequested: Boolean,
    ) : KastMutationOperationState

    @Serializable
    @SerialName("FAILED")
    data class Failed(
        val failure: KastMutationFailure,
        override val trace: KastMutationExecutionTrace,
        override val cancellationRequested: Boolean,
    ) : KastMutationOperationState

    @Serializable
    @SerialName("CANCELLED")
    data class Cancelled(
        val message: String,
        override val trace: KastMutationExecutionTrace,
        override val cancellationRequested: Boolean = true,
    ) : KastMutationOperationState {
        init {
            require(message.isNotBlank()) { "Mutation cancellation outcome requires a message" }
            require(cancellationRequested) { "Cancelled mutation must retain its cancellation request" }
        }
    }

    companion object {
        private val APPLYING_STAGES = setOf(
            KastMutationProgressStage.IDENTITY_RESOLUTION,
            KastMutationProgressStage.EDIT_APPLICATION,
        )
        private val VALIDATING_STAGES = setOf(
            KastMutationProgressStage.WORKSPACE_REFRESH,
            KastMutationProgressStage.IMPORT_OPTIMIZATION,
            KastMutationProgressStage.DIAGNOSTICS,
        )
    }
}
