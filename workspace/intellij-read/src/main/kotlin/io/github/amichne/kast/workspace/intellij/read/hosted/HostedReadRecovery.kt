package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Guidance is conditional on a new observation; no retry deadline or active import is inferred. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface HostedReadRecovery {
    @Serializable
    @SerialName("after_state_change")
    class GradleModel private constructor() : HostedReadRecovery {
        companion object { val Required = GradleModel() }
        val check: HostedReadinessCheck = HostedReadinessCheck.IDE_STATUS
        val required: HostedReadinessRequirement = HostedReadinessRequirement.CACHED_GRADLE_MODEL_COMPLETE
        val remediation: HostedReadinessRemediation = HostedReadinessRemediation.OBSERVE_IDE_GRADLE_MODEL
    }

    @Serializable
    @SerialName("after_indexing")
    class Indexing private constructor() : HostedReadRecovery {
        companion object { val Required = Indexing() }
        val check: HostedReadinessCheck = HostedReadinessCheck.IDE_STATUS
        val required: HostedReadinessRequirement = HostedReadinessRequirement.SMART_MODE
        val remediation: HostedReadinessRemediation = HostedReadinessRemediation.WAIT_FOR_IDE_INDEXING
    }

    @Serializable
    @SerialName("review_failure")
    data object ReviewFailure : HostedReadRecovery
}

@Serializable
enum class HostedReadinessCheck { IDE_STATUS }

@Serializable
enum class HostedReadinessRequirement { CACHED_GRADLE_MODEL_COMPLETE, SMART_MODE }

@Serializable
enum class HostedReadinessRemediation { OBSERVE_IDE_GRADLE_MODEL, WAIT_FOR_IDE_INDEXING }

internal fun HostedQueryFailure.recovery(): HostedReadRecovery = when (this) {
    is HostedQueryFailure.ProjectAdmission -> when (cause) {
        ExistingProjectAdmissionFailure.GradleModelUnavailable,
        ExistingProjectAdmissionFailure.GradleModelIncomplete -> HostedReadRecovery.GradleModel.Required
        ExistingProjectAdmissionFailure.DumbMode -> HostedReadRecovery.Indexing.Required
        else -> HostedReadRecovery.ReviewFailure
    }
    is HostedQueryFailure.ReadEpoch -> when (cause) {
        ProjectReadEpochObservationFailure.GradleModelUnavailable,
        ProjectReadEpochObservationFailure.GradleModelIncomplete -> HostedReadRecovery.GradleModel.Required
        ProjectReadEpochObservationFailure.DumbMode -> HostedReadRecovery.Indexing.Required
        else -> HostedReadRecovery.ReviewFailure
    }
    is HostedQueryFailure.Freshness -> when (val failure = cause) {
        VfsPassiveReadAdmissionFailure.DumbMode -> HostedReadRecovery.Indexing.Required
        is VfsPassiveReadAdmissionFailure.Unavailable -> when (failure.cause) {
            VfsPassiveReadUnavailableCause.GradleModelUnavailable,
            VfsPassiveReadUnavailableCause.GradleModelIncomplete -> HostedReadRecovery.GradleModel.Required
            else -> HostedReadRecovery.ReviewFailure
        }
        else -> HostedReadRecovery.ReviewFailure
    }
    HostedQueryFailure.INDEXING -> HostedReadRecovery.Indexing.Required
    else -> HostedReadRecovery.ReviewFailure
}
