package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Finite diagnostic identities; raw arguments, environment, and child output are never projected. */
@Serializable
internal enum class LifecycleChildAction {
    DISABLE,
    BOOTSTRAP,
    INSTALL,
    UNSUPPORTED,
}

@Serializable
internal enum class LifecycleChildStage {
    START,
    WAIT,
}

@Serializable
internal enum class LifecycleChildUnavailability {
    START_REJECTED,
    OBSERVATION_REJECTED,
    INTERRUPTED,
}

@Serializable
internal sealed interface LifecycleChildExecutionObservation {
    val action: LifecycleChildAction
    val purpose: LifecycleChildPurpose
    val stage: LifecycleChildStage

    @Serializable
    @SerialName("STARTED")
    data class Started(override val action: LifecycleChildAction, override val purpose: LifecycleChildPurpose) :
        LifecycleChildExecutionObservation {
        override val stage = LifecycleChildStage.START
        val component: String = "kast-lifecycle-child"
    }

    @Serializable
    @SerialName("EXITED")
    data class Exited(
        override val action: LifecycleChildAction,
        override val purpose: LifecycleChildPurpose,
        val exitCode: Int,
    ) : LifecycleChildExecutionTerminal {
        override val stage = LifecycleChildStage.WAIT
        val component: String = "kast-lifecycle-child"
    }

    @Serializable
    @SerialName("DEADLINE_EXCEEDED")
    data class DeadlineExceeded(
        override val action: LifecycleChildAction,
        override val purpose: LifecycleChildPurpose,
    ) : LifecycleChildExecutionTerminal {
        override val stage = LifecycleChildStage.WAIT
        val component: String = "kast-lifecycle-child"
    }

    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(
        override val action: LifecycleChildAction,
        override val purpose: LifecycleChildPurpose,
        val failure: LifecycleChildUnavailability,
    ) : LifecycleChildExecutionTerminal {
        override val stage =
            when (failure) {
                LifecycleChildUnavailability.START_REJECTED -> LifecycleChildStage.START
                LifecycleChildUnavailability.OBSERVATION_REJECTED,
                LifecycleChildUnavailability.INTERRUPTED -> LifecycleChildStage.WAIT
            }
        val component: String = "kast-lifecycle-child"
    }
}

@Serializable internal sealed interface LifecycleChildExecutionTerminal : LifecycleChildExecutionObservation

internal fun LifecycleChildExecutionTerminal.asChildObservation(): LifecycleChildObservation =
    when (this) {
        is LifecycleChildExecutionObservation.Exited -> LifecycleChildObservation.Exited(exitCode)
        is LifecycleChildExecutionObservation.DeadlineExceeded -> LifecycleChildObservation.DeadlineExceeded
        is LifecycleChildExecutionObservation.Unavailable -> LifecycleChildObservation.Unavailable
    }

internal fun lifecycleChildAction(command: List<String>, purpose: LifecycleChildPurpose): LifecycleChildAction =
    when (purpose) {
        LifecycleChildPurpose.INSTALLER -> LifecycleChildAction.INSTALL
        LifecycleChildPurpose.SERVICE ->
            when (command.lastOrNull()) {
                "disable" -> LifecycleChildAction.DISABLE
                "bootstrap" -> LifecycleChildAction.BOOTSTRAP
                else -> LifecycleChildAction.UNSUPPORTED
            }
    }
