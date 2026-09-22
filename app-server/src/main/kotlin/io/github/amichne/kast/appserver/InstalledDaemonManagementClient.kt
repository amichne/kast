package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.runtime.BrokerControlRoute
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnection
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnectionAdmission
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamFrame
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamSend
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
                CanonicalBrokerDirectory.admit(workspace.toRealPath())
            } catch (_: Exception) {
                null
            } ?: return Refinement.Rejected(DaemonManagementRejection.Enrollment(EnrollmentFailure.PATH_REJECTED))
        val status =
            when (val read = InstalledCoordinatorClient(kast).status(command)) {
                is CoordinatorStatusRead.Observed -> read.snapshot
                is CoordinatorStatusRead.Rejected ->
                    return Refinement.Rejected(DaemonManagementRejection.Coordinator(read.failure))
            }
        return register(command.publicSocket, DaemonManagementTarget.from(status), root)
    }

    private suspend fun register(
        socket: Path,
        target: DaemonManagementTarget,
        root: CanonicalBrokerDirectory,
    ): Refinement<WorkspaceRegistrationAcknowledgement, DaemonManagementRejection> =
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
                exchangeDaemonRegistration(connection, target, root)
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
    if (
        connection.send(DaemonManagementProtocol.json.encodeToString(DaemonManagementRequest.serializer(), request)) !=
            BrokerUpstreamSend.SENT
    )
        return Refinement.Rejected(DaemonManagementRejection.Protocol(DaemonManagementFailure.OUTCOME_UNOBSERVED))
    val frame = connection.receive()
    if (frame !is BrokerUpstreamFrame.Text)
        return Refinement.Rejected(DaemonManagementRejection.Protocol(DaemonManagementFailure.OUTCOME_UNOBSERVED))
    return admitDaemonRegistration(frame.message, target, root)
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
    return when (response) {
        is DaemonManagementResponse.Rejected -> Refinement.Rejected(response.reason)
        is DaemonManagementResponse.Status -> reject()
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
