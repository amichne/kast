package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.ide.HostExit
import io.github.amichne.kast.appserver.ide.HostExitCause
import io.github.amichne.kast.distribution.contract.HostExitIndexState
import io.github.amichne.kast.distribution.contract.HostExitReason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class CoordinatorHostExitEvidence(
    val host: String,
    val pid: Long,
    val startedEpochMillis: Long,
    val process: ExitedProcess,
    val cause: ExitEvidence,
    val event: String = "kast_coordinator_host_exit",
)

@Serializable
private sealed interface ExitedProcess {
    @Serializable @SerialName("owned_child") data class OwnedChild(val exitStatus: Int) : ExitedProcess

    @Serializable @SerialName("adopted") data object Adopted : ExitedProcess
}

@Serializable
private sealed interface ExitEvidence {
    @Serializable
    @SerialName("requested_stop")
    data class RequestedStop(val reason: HostExitReason, val index: HostExitIndexState) : ExitEvidence

    @Serializable @SerialName("unknown") data object Unknown : ExitEvidence
}

internal fun recordCoordinatorHostExit(exit: HostExit) {
    val evidence =
        CoordinatorHostExitEvidence(
            exit.incarnation.hostLifetime,
            exit.incarnation.pid,
            exit.incarnation.processStartEpochMillis,
            when (exit) {
                is HostExit.OwnedChild -> ExitedProcess.OwnedChild(exit.status)
                is HostExit.Adopted -> ExitedProcess.Adopted
            },
            when (val cause = exit.cause) {
                is HostExitCause.RequestedStop -> ExitEvidence.RequestedStop(cause.reason, cause.index)
                HostExitCause.Unknown -> ExitEvidence.Unknown
            },
        )
    System.err.println(exitJson.encodeToString(CoordinatorHostExitEvidence.serializer(), evidence))
}

private val exitJson = Json { encodeDefaults = true }
