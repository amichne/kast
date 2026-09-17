package io.github.amichne.kast.cli.installation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Installation is committed before activation; pending activation never erases that fact. */
@Serializable
internal sealed interface InstallationActivation {
    @Serializable @SerialName("planned") data object Planned : InstallationActivation

    @Serializable @SerialName("not-requested") data object NotRequested : InstallationActivation

    @Serializable @SerialName("ready") data object Ready : InstallationActivation

    @Serializable
    @SerialName("pending")
    data class Pending(val reason: InstallationActivationFailure) : InstallationActivation {
        val resume: InstallationActivationResume = InstallationActivationResume.KAST_CODEX
    }

    companion object {
        fun fromChild(outcome: InstallationChildOutcome): InstallationActivation =
            when (outcome) {
                InstallationChildOutcome.COMPLETED -> Ready
                InstallationChildOutcome.EXIT_REJECTED -> Pending(InstallationActivationFailure.EXIT_REJECTED)
                InstallationChildOutcome.DEADLINE_EXCEEDED -> Pending(InstallationActivationFailure.DEADLINE_EXCEEDED)
                InstallationChildOutcome.IO_REJECTED -> Pending(InstallationActivationFailure.IO_REJECTED)
                InstallationChildOutcome.INTERRUPTED -> Pending(InstallationActivationFailure.INTERRUPTED)
            }
    }
}

@Serializable
internal enum class InstallationActivationFailure {
    EXIT_REJECTED,
    DEADLINE_EXCEEDED,
    IO_REJECTED,
    INTERRUPTED,
}

@Serializable
internal enum class InstallationActivationResume {
    @SerialName("kast codex") KAST_CODEX
}

@Serializable
internal enum class InstallationReportStatus {
    @SerialName("planned") PLANNED,
    @SerialName("installed") INSTALLED,
    @SerialName("installed-activation-pending") INSTALLED_ACTIVATION_PENDING,
}

internal val InstallationActivation.installationStatus: InstallationReportStatus
    get() =
        when (this) {
            InstallationActivation.Planned -> InstallationReportStatus.PLANNED
            InstallationActivation.NotRequested,
            InstallationActivation.Ready -> InstallationReportStatus.INSTALLED
            is InstallationActivation.Pending -> InstallationReportStatus.INSTALLED_ACTIVATION_PENDING
        }
