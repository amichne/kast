package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal enum class LifecycleOperation {
    STOP,
    STOP_FORCE,
    REINSTALL,
}

@Serializable
internal enum class LifecycleStage {
    ADMISSION,
    FENCE,
    COORDINATOR,
    REQUESTS,
    HOST,
    INSTALLATION,
    REGISTRATIONS,
    ACTIVATION,
}

@Serializable
internal enum class LifecycleFailure {
    OWNERSHIP_UNPROVEN,
    FENCE_REJECTED,
    CHILD_REJECTED,
    CHILD_DEADLINE_EXCEEDED,
    REQUEST_OWNERSHIP_UNPROVEN,
    REQUESTS_DID_NOT_RETIRE,
    HOST_OBSERVATION_REJECTED,
    HOST_RESTART_REQUIRED,
    INSTALLATION_REJECTED,
    REGISTRATION_REPAIR_REQUIRED,
    FILESYSTEM_REJECTED,
}

@Serializable
internal sealed interface LifecycleOutcome {
    val operation: LifecycleOperation

    @Serializable
    @SerialName("STOPPED")
    data class Stopped(override val operation: LifecycleOperation, val installation: String) : LifecycleOutcome

    @Serializable
    @SerialName("REINSTALLED")
    data class Reinstalled(override val operation: LifecycleOperation, val installation: String, val version: String) :
        LifecycleOutcome

    @Serializable
    @SerialName("REJECTED")
    data class Rejected(
        override val operation: LifecycleOperation,
        val stage: LifecycleStage,
        val failure: LifecycleFailure,
    ) : LifecycleOutcome

    @Serializable
    @SerialName("REINSTALLATION_PENDING")
    data class Pending(
        override val operation: LifecycleOperation,
        val version: String,
        val stage: LifecycleStage,
        val failure: LifecycleFailure,
    ) : LifecycleOutcome
}

internal fun LifecycleOutcome.asJson(): String = managementJson.encodeToString<LifecycleOutcome>(this)

@Serializable
internal enum class LifecycleObservationOutcome {
    STARTED,
    COMPLETED,
    REJECTED,
}

@Serializable
internal sealed interface LifecycleObservation {
    val operation: LifecycleOperation
    val stage: LifecycleStage
    val component: String

    val outcome: LifecycleObservationOutcome
        get() =
            when (this) {
                is Started -> LifecycleObservationOutcome.STARTED
                is Completed -> LifecycleObservationOutcome.COMPLETED
                is Rejected -> LifecycleObservationOutcome.REJECTED
            }

    @Serializable
    @SerialName("STARTED")
    data class Started(
        override val operation: LifecycleOperation,
        override val stage: LifecycleStage,
        override val component: String = "kast-lifecycle",
    ) : LifecycleObservation

    @Serializable
    @SerialName("COMPLETED")
    data class Completed(
        override val operation: LifecycleOperation,
        override val stage: LifecycleStage,
        override val component: String = "kast-lifecycle",
    ) : LifecycleObservation

    @Serializable
    @SerialName("REJECTED")
    data class Rejected(
        override val operation: LifecycleOperation,
        override val stage: LifecycleStage,
        val failure: LifecycleFailure,
        override val component: String = "kast-lifecycle",
    ) : LifecycleObservation

    companion object {
        fun started(operation: LifecycleOperation, stage: LifecycleStage): LifecycleObservation =
            Started(operation, stage)

        fun completed(operation: LifecycleOperation, stage: LifecycleStage): LifecycleObservation =
            Completed(operation, stage)

        fun rejected(
            operation: LifecycleOperation,
            stage: LifecycleStage,
            failure: LifecycleFailure,
        ): LifecycleObservation = Rejected(operation, stage, failure)
    }
}

internal sealed interface LifecycleEffect {
    data object Completed : LifecycleEffect

    data class Rejected(val failure: LifecycleFailure) : LifecycleEffect
}

internal sealed interface LifecycleChildObservation {
    data class Exited(val code: Int) : LifecycleChildObservation

    data object DeadlineExceeded : LifecycleChildObservation

    data object Unavailable : LifecycleChildObservation
}

internal fun interface LifecycleChildExecutor {
    fun execute(
        command: List<String>,
        directory: java.nio.file.Path,
        environment: Map<String, String>,
    ): LifecycleChildObservation
}
