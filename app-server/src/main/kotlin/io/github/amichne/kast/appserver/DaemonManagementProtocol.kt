package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.ControlFailure
import io.github.amichne.kast.appserver.runtime.DaemonSessionInspection
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Versioned local management protocol, independent of the optional Codex session protocol. */
internal object DaemonManagementProtocol {
    const val route = "/kast-management"
    const val version = 1
    const val maximumBytes = 16_384
    val json = Json { encodeDefaults = true }
}

@Serializable
internal data class DaemonManagementTarget(
    val installationId: String,
    val stateEpoch: String,
    val serviceGeneration: String,
    val configurationIdentity: String,
) {
    companion object {
        fun from(status: CoordinatorStatusSnapshot): DaemonManagementTarget =
            with(status.observed) {
                DaemonManagementTarget(installationId, stateEpoch, serviceGeneration, configurationIdentity)
            }
    }
}

@Serializable
internal sealed interface DaemonManagementRequest {
    val version: Int

    @Serializable
    @SerialName("status")
    data class Status(@Required override val version: Int = DaemonManagementProtocol.version) : DaemonManagementRequest

    @Serializable
    @SerialName("sessions")
    data class Sessions(
        val target: DaemonManagementTarget,
        @Required override val version: Int = DaemonManagementProtocol.version,
    ) : DaemonManagementRequest

    @Serializable
    @SerialName("control")
    data class Control(
        val target: DaemonManagementTarget,
        val operation: ControlOperation,
        val threadId: String,
        val connectionId: String,
        @Required override val version: Int = DaemonManagementProtocol.version,
    ) : DaemonManagementRequest

    @Serializable
    @SerialName("register_workspace")
    data class RegisterWorkspace(
        val target: DaemonManagementTarget,
        val root: String,
        @Required override val version: Int = DaemonManagementProtocol.version,
    ) : DaemonManagementRequest
}

@Serializable
internal sealed interface DaemonManagementResponse {
    @Serializable
    @SerialName("status")
    data class Status(val coordinator: CoordinatorStatusDocument) : DaemonManagementResponse

    @Serializable
    @SerialName("registered")
    data class Registered(
        val target: DaemonManagementTarget,
        val workspaceId: String,
        val root: String,
        val revision: Long,
    ) : DaemonManagementResponse

    @Serializable
    @SerialName("sessions")
    data class Sessions(val target: DaemonManagementTarget, val inspection: DaemonSessionInspection) :
        DaemonManagementResponse

    @Serializable
    @SerialName("controlled")
    data class Controlled(
        val target: DaemonManagementTarget,
        val operation: ControlOperation,
        val threadId: String,
        val connectionId: String,
    ) : DaemonManagementResponse

    @Serializable
    @SerialName("rejected")
    data class Rejected(val reason: DaemonManagementRejection) : DaemonManagementResponse
}

@Serializable
sealed interface DaemonManagementRejection {
    @Serializable
    @SerialName("protocol")
    data class Protocol(val failure: DaemonManagementFailure) : DaemonManagementRejection

    @Serializable
    @SerialName("coordinator")
    data class Coordinator(val failure: WorkerControlFailure) : DaemonManagementRejection

    @Serializable
    @SerialName("enrollment")
    data class Enrollment(val failure: EnrollmentFailure) : DaemonManagementRejection

    @Serializable @SerialName("control") data class Control(val failure: ControlFailure) : DaemonManagementRejection
}

@Serializable
enum class DaemonManagementFailure {
    INVALID_REQUEST,
    UNSUPPORTED_VERSION,
    IDENTITY_REJECTED,
    LIFECYCLE_TRANSITION,
    UNAVAILABLE,
    OUTCOME_UNOBSERVED,
    RESPONSE_REJECTED,
    CAPACITY_EXCEEDED,
}

@Serializable
internal data class WorkspaceRegistrationDocument(
    val workspaceId: String,
    val root: String,
    val revision: Long,
    val operation: String = "app-server.register",
)

fun DaemonManagementRejection.diagnosticCode(): String =
    when (this) {
            is DaemonManagementRejection.Protocol -> "daemon-management-${failure.name}"
            is DaemonManagementRejection.Coordinator -> "coordinator-${failure.name}"
            is DaemonManagementRejection.Control -> "control-${failure.name}"
            is DaemonManagementRejection.Enrollment -> "enrollment-${failure.name}"
        }
        .lowercase()
        .replace('_', '-')
