package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.runtime.BrokerControlRoute
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnection
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnectionAdmission
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamFrame
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamSend
import io.github.amichne.kast.appserver.runtime.DaemonSessionInspection
import io.github.amichne.kast.appserver.runtime.connectCodexUnixWebSocket
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

/** Qualifies published ownership before sending effects; never retries an unobserved registration. */
internal class InstalledDaemonManagementClient(private val kast: Path) {
    suspend fun register(
        command: BrokerServiceLaunchCommand,
        workspace: Path,
    ): Refinement<WorkspaceRegistrationAcknowledgement, DaemonManagementRejection> {
        val root =
            try {
                workspace.takeIf(Path::isAbsolute)?.toRealPath()?.let(CanonicalBrokerDirectory::admit)
            } catch (_: Exception) {
                null
            } ?: return Refinement.Rejected(DaemonManagementRejection.Enrollment(EnrollmentFailure.PATH_REJECTED))
        val target =
            when (val admitted = target(command)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        return exchange(command.publicSocket) { exchangeDaemonRegistration(it, target, root) }
    }

    suspend fun control(
        command: BrokerServiceLaunchCommand,
        action: AppServerAction.Control,
    ): Refinement<DaemonManagementResponse.Controlled, DaemonManagementRejection> {
        val target =
            when (val admitted = target(command)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val request =
            DaemonManagementRequest.Control(
                target,
                action.operation,
                action.target.threadId,
                action.target.connectionId,
            )
        return exchange(command.publicSocket) { connection ->
            when (val response = exchangeDaemonManagement(connection, request)) {
                is Refinement.Rejected -> response
                is Refinement.Refined -> admitDaemonControl(response.value, request)
            }
        }
    }

    suspend fun sessions(
        command: BrokerServiceLaunchCommand
    ): Refinement<DaemonSessionInspection, DaemonManagementRejection> {
        val target =
            when (val admitted = target(command)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        return exchange(command.publicSocket) { connection ->
            when (val response = exchangeDaemonManagement(connection, DaemonManagementRequest.Sessions(target))) {
                is Refinement.Rejected -> response
                is Refinement.Refined -> {
                    val value = response.value
                    if (value is DaemonManagementResponse.Sessions && value.target == target)
                        Refinement.Refined(value.inspection)
                    else rejected(DaemonManagementFailure.RESPONSE_REJECTED)
                }
            }
        }
    }

    private suspend fun target(
        command: BrokerServiceLaunchCommand
    ): Refinement<DaemonManagementTarget, DaemonManagementRejection> =
        when (val read = InstalledCoordinatorClient(kast).status(command)) {
            is CoordinatorStatusRead.Observed -> Refinement.Refined(DaemonManagementTarget.from(read.snapshot))
            is CoordinatorStatusRead.Rejected ->
                Refinement.Rejected(DaemonManagementRejection.Coordinator(read.failure))
        }

    private suspend fun <T> exchange(
        socket: Path,
        action: suspend (BrokerUpstreamConnection) -> Refinement<T, DaemonManagementRejection>,
    ): Refinement<T, DaemonManagementRejection> =
        withTimeoutOrNull(BrokerOperationalLimits.managementExchange.value) {
            val connection =
                when (
                    val connected =
                        connectCodexUnixWebSocket(
                            socket,
                            DaemonManagementProtocol.maximumBytes,
                            BrokerOperationalLimits.managementConnect.value,
                            BrokerControlRoute.MANAGEMENT,
                        )
                ) {
                    is BrokerUpstreamConnectionAdmission.Connected -> connected.connection
                    BrokerUpstreamConnectionAdmission.Rejected ->
                        return@withTimeoutOrNull rejected(DaemonManagementFailure.UNAVAILABLE)
                }
            try {
                action(connection)
            } finally {
                withContext(NonCancellable) { connection.close() }
            }
        } ?: rejected(DaemonManagementFailure.OUTCOME_UNOBSERVED)

    private fun rejected(failure: DaemonManagementFailure) =
        Refinement.Rejected(DaemonManagementRejection.Protocol(failure))
}

internal suspend fun exchangeDaemonRegistration(
    connection: BrokerUpstreamConnection,
    target: DaemonManagementTarget,
    root: CanonicalBrokerDirectory,
): Refinement<WorkspaceRegistrationAcknowledgement, DaemonManagementRejection> {
    val request: DaemonManagementRequest = DaemonManagementRequest.RegisterWorkspace(target, root.path.toString())
    return when (val response = exchangeDaemonManagement(connection, request)) {
        is Refinement.Rejected -> response
        is Refinement.Refined -> admitDaemonRegistration(response.value, target, root)
    }
}

internal suspend fun exchangeDaemonManagement(
    connection: BrokerUpstreamConnection,
    request: DaemonManagementRequest,
): Refinement<DaemonManagementResponse, DaemonManagementRejection> {
    if (
        connection.send(DaemonManagementProtocol.json.encodeToString(DaemonManagementRequest.serializer(), request)) !=
            BrokerUpstreamSend.SENT
    )
        return Refinement.Rejected(DaemonManagementRejection.Protocol(DaemonManagementFailure.OUTCOME_UNOBSERVED))
    val frame = connection.receive()
    if (frame !is BrokerUpstreamFrame.Text)
        return Refinement.Rejected(DaemonManagementRejection.Protocol(DaemonManagementFailure.OUTCOME_UNOBSERVED))
    val response =
        try {
            DaemonManagementProtocol.json.decodeFromString<DaemonManagementResponse>(frame.message)
        } catch (_: SerializationException) {
            return Refinement.Rejected(DaemonManagementRejection.Protocol(DaemonManagementFailure.RESPONSE_REJECTED))
        }
    return if (response is DaemonManagementResponse.Rejected) Refinement.Rejected(response.reason)
    else Refinement.Refined(response)
}

internal fun admitDaemonControl(
    response: DaemonManagementResponse,
    request: DaemonManagementRequest.Control,
): Refinement<DaemonManagementResponse.Controlled, DaemonManagementRejection> =
    when {
        response is DaemonManagementResponse.Rejected -> Refinement.Rejected(response.reason)
        response is DaemonManagementResponse.Controlled &&
            response.target == request.target &&
            response.operation == request.operation &&
            response.threadId == request.threadId &&
            response.connectionId == request.connectionId -> Refinement.Refined(response)
        else -> Refinement.Rejected(DaemonManagementRejection.Protocol(DaemonManagementFailure.RESPONSE_REJECTED))
    }

internal fun admitDaemonRegistration(
    raw: String,
    target: DaemonManagementTarget,
    root: CanonicalBrokerDirectory,
): Refinement<WorkspaceRegistrationAcknowledgement, DaemonManagementRejection> {
    fun reject() = Refinement.Rejected(DaemonManagementRejection.Protocol(DaemonManagementFailure.RESPONSE_REJECTED))
    val response =
        try {
            DaemonManagementProtocol.json.decodeFromString<DaemonManagementResponse>(raw)
        } catch (_: SerializationException) {
            return reject()
        }
    return admitDaemonRegistration(response, target, root)
}

private fun admitDaemonRegistration(
    response: DaemonManagementResponse,
    target: DaemonManagementTarget,
    root: CanonicalBrokerDirectory,
): Refinement<WorkspaceRegistrationAcknowledgement, DaemonManagementRejection> {
    fun reject() = Refinement.Rejected(DaemonManagementRejection.Protocol(DaemonManagementFailure.RESPONSE_REJECTED))
    return when (response) {
        is DaemonManagementResponse.Rejected -> Refinement.Rejected(response.reason)
        is DaemonManagementResponse.WorkspacePreparation,
        is DaemonManagementResponse.Status,
        is DaemonManagementResponse.Sessions,
        is DaemonManagementResponse.Controlled -> reject()
        is DaemonManagementResponse.Registered -> {
            val workspace = WorkspaceRegistration(root)
            val revision =
                WorkspaceRegistryRevision.admit(response.revision)?.takeIf { it != WorkspaceRegistryRevision.Empty }
                    ?: return reject()
            if (
                response.target != target ||
                    response.root != root.path.toString() ||
                    response.workspaceId != workspace.id.value
            )
                reject()
            else Refinement.Refined(WorkspaceRegistrationAcknowledgement(workspace, revision))
        }
    }
}
