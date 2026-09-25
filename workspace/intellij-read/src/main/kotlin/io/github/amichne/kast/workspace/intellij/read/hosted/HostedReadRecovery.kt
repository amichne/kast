package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Guidance is conditional on a new observation; no retry deadline or active import is inferred. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface HostedReadRecovery {
    @Serializable
    @SerialName("increase_host_deadline")
    data object IncreaseHostDeadline : HostedReadRecovery {
        @kotlinx.serialization.EncodeDefault
        val instruction: String =
            "The host deadline expired during project admission or cached Gradle model capture, before semantic search. Inspect the kast_semantic_read stage and elapsed time; narrow search scope only after semantic search begins. Increase the host query, connection, and client exchange deadlines together if this project consistently needs more preparation time."
    }

    @Serializable
    @SerialName("after_state_change")
    class GradleModel private constructor() : HostedReadRecovery {
        companion object {
            val Required = GradleModel()
        }

        val check: HostedReadinessCheck = HostedReadinessCheck.IDE_STATUS
        val required: HostedReadinessRequirement = HostedReadinessRequirement.CACHED_GRADLE_MODEL_COMPLETE
        val remediation: HostedReadinessRemediation = HostedReadinessRemediation.OBSERVE_IDE_GRADLE_MODEL
    }

    @Serializable
    @SerialName("after_indexing")
    class Indexing private constructor() : HostedReadRecovery {
        companion object {
            val Required = Indexing()
        }

        val check: HostedReadinessCheck = HostedReadinessCheck.IDE_STATUS
        val required: HostedReadinessRequirement = HostedReadinessRequirement.SMART_MODE
        val remediation: HostedReadinessRemediation = HostedReadinessRemediation.WAIT_FOR_IDE_INDEXING
    }

    @Serializable
    @SerialName("reduce_read_work")
    class ReduceReadWork private constructor() : HostedReadRecovery {
        companion object {
            val Required = ReduceReadWork()
        }

        @kotlinx.serialization.EncodeDefault
        val instruction: String =
            "The hosted read exhausted its time allowance before publishing a validated result. Narrow the file, directory, source-set or traversal scope. Inspect execution_budget for caller limits and host clamps before increasing max_elapsed_ms."
    }

    @Serializable
    @SerialName("cancelled")
    class Cancelled private constructor() : HostedReadRecovery {
        companion object {
            val Required = Cancelled()
        }

        @kotlinx.serialization.EncodeDefault
        val instruction: String =
            "The read was cancelled. Start a new read only if the result is still needed; cancellation does not prove budget exhaustion."
    }

    @Serializable
    @SerialName("save_source")
    class SaveSource private constructor() : HostedReadRecovery {
        companion object {
            val Required = SaveSource()
        }

        @kotlinx.serialization.EncodeDefault
        val instruction: String =
            "Save the affected documents and allow IDE document-to-PSI synchronization, then start a new read."
    }

    @Serializable
    @SerialName("wait_for_capacity")
    class WaitForCapacity private constructor() : HostedReadRecovery {
        companion object {
            val Required = WaitForCapacity()
        }

        @kotlinx.serialization.EncodeDefault
        val instruction: String = "Allow the active read to finish before issuing another request to this host."
    }

    @Serializable
    @SerialName("restart_read")
    class RestartRead private constructor() : HostedReadRecovery {
        companion object {
            val Required = RestartRead()
        }

        @kotlinx.serialization.EncodeDefault
        val instruction: String =
            "The model or source changed during the read. Start a fresh read; a continuation from the old epoch cannot establish current evidence."
    }

    @Serializable @SerialName("review_failure") data object ReviewFailure : HostedReadRecovery
}

@Serializable
enum class HostedReadinessCheck {
    IDE_STATUS
}

@Serializable
enum class HostedReadinessRequirement {
    CACHED_GRADLE_MODEL_COMPLETE,
    SMART_MODE,
}

@Serializable
enum class HostedReadinessRemediation {
    OBSERVE_IDE_GRADLE_MODEL,
    WAIT_FOR_IDE_INDEXING,
}

internal fun HostedQueryFailure.recovery(): HostedReadRecovery =
    when (this) {
        is HostedQueryFailure.ProjectAdmission ->
            when (cause) {
                ExistingProjectAdmissionFailure.GradleModelUnavailable,
                ExistingProjectAdmissionFailure.GradleModelIncomplete -> HostedReadRecovery.GradleModel.Required
                ExistingProjectAdmissionFailure.DumbMode -> HostedReadRecovery.Indexing.Required
                else -> HostedReadRecovery.ReviewFailure
            }
        is HostedQueryFailure.ReadEpoch ->
            when (cause) {
                ProjectReadEpochObservationFailure.GradleModelUnavailable,
                ProjectReadEpochObservationFailure.GradleModelIncomplete -> HostedReadRecovery.GradleModel.Required
                ProjectReadEpochObservationFailure.DumbMode -> HostedReadRecovery.Indexing.Required
                else -> HostedReadRecovery.ReviewFailure
            }
        is HostedQueryFailure.Freshness ->
            when (val failure = cause) {
                VfsPassiveReadAdmissionFailure.DumbMode -> HostedReadRecovery.Indexing.Required
                is VfsPassiveReadAdmissionFailure.Unavailable ->
                    when (failure.cause) {
                        VfsPassiveReadUnavailableCause.GradleModelUnavailable,
                        VfsPassiveReadUnavailableCause.GradleModelIncomplete -> HostedReadRecovery.GradleModel.Required
                        else -> HostedReadRecovery.ReviewFailure
                    }
                else -> HostedReadRecovery.ReviewFailure
            }
        HostedQueryFailure.BUDGET_EXCEEDED -> HostedReadRecovery.ReduceReadWork.Required
        HostedQueryFailure.CANCELLED -> HostedReadRecovery.Cancelled.Required
        HostedQueryFailure.DIRTY_DOCUMENTS,
        HostedQueryFailure.UNCOMMITTED_DOCUMENTS -> HostedReadRecovery.SaveSource.Required
        HostedQueryFailure.BUSY -> HostedReadRecovery.WaitForCapacity.Required
        HostedQueryFailure.STALE_REQUEST,
        HostedQueryFailure.CONTENT_MOVED,
        HostedQueryFailure.MODEL_MOVED,
        HostedQueryFailure.READ_PREEMPTED -> HostedReadRecovery.RestartRead.Required
        HostedQueryFailure.INDEXING -> HostedReadRecovery.Indexing.Required
        else -> HostedReadRecovery.ReviewFailure
    }

internal fun HostedQueryFailure.recovery(stage: HostedQueryStage): HostedReadRecovery =
    if (this == HostedQueryFailure.BUDGET_EXCEEDED && stage.ordinal <= HostedQueryStage.MODEL_CAPTURE.ordinal)
        HostedReadRecovery.IncreaseHostDeadline
    else recovery()
