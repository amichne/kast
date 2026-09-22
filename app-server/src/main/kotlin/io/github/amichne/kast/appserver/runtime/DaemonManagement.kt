package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.CoordinatorStatusDocument
import io.github.amichne.kast.appserver.DaemonManagementFailure
import io.github.amichne.kast.appserver.DaemonManagementProtocol
import io.github.amichne.kast.appserver.DaemonManagementRejection
import io.github.amichne.kast.appserver.DaemonManagementRequest
import io.github.amichne.kast.appserver.DaemonManagementResponse
import io.github.amichne.kast.appserver.DaemonManagementTarget
import io.github.amichne.kast.appserver.EnrollmentFailure
import io.github.amichne.kast.appserver.WorkspaceRegistration
import io.github.amichne.kast.appserver.WorkspaceRegistrationAcknowledgement
import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.SerializationException

/** The existing coordinator owns lifecycle admission; enrollment owns filesystem admission and atomic writes. */
internal class DaemonManagement(
    private val target: DaemonManagementTarget,
    private val available: () -> Boolean,
    private val status: () -> CoordinatorStatusDocument,
    private val enroll:
        (CanonicalBrokerDirectory) -> Refinement<WorkspaceRegistrationAcknowledgement, EnrollmentFailure>,
) {
    fun exchange(raw: String): String {
        val response =
            if (raw.toByteArray(Charsets.UTF_8).size > DaemonManagementProtocol.maximumBytes) {
                reject(DaemonManagementFailure.CAPACITY_EXCEEDED)
            } else {
                try {
                    execute(DaemonManagementProtocol.json.decodeFromString<DaemonManagementRequest>(raw))
                } catch (_: SerializationException) {
                    reject(DaemonManagementFailure.INVALID_REQUEST)
                }
            }
        val encoded = DaemonManagementProtocol.json.encodeToString(DaemonManagementResponse.serializer(), response)
        return if (encoded.toByteArray(Charsets.UTF_8).size <= DaemonManagementProtocol.maximumBytes) encoded
        else
            DaemonManagementProtocol.json.encodeToString(
                DaemonManagementResponse.serializer(),
                reject(DaemonManagementFailure.CAPACITY_EXCEEDED),
            )
    }

    internal fun execute(request: DaemonManagementRequest): DaemonManagementResponse {
        if (request.version != DaemonManagementProtocol.version)
            return reject(DaemonManagementFailure.UNSUPPORTED_VERSION)
        if (!available()) return reject(DaemonManagementFailure.LIFECYCLE_TRANSITION)
        return when (request) {
            is DaemonManagementRequest.Status -> DaemonManagementResponse.Status(status())
            is DaemonManagementRequest.RegisterWorkspace -> register(request)
        }
    }

    private fun register(request: DaemonManagementRequest.RegisterWorkspace): DaemonManagementResponse {
        if (request.target != target) return reject(DaemonManagementFailure.IDENTITY_REJECTED)
        val root =
            try {
                val path = Path.of(request.root)
                if (path.isAbsolute) CanonicalBrokerDirectory.admit(path.toRealPath()) else null
            } catch (_: Exception) {
                null
            }
                ?: return DaemonManagementResponse.Rejected(
                    DaemonManagementRejection.Enrollment(EnrollmentFailure.PATH_REJECTED)
                )
        val maximumReply: DaemonManagementResponse =
            DaemonManagementResponse.Registered(
                target,
                WorkspaceRegistration(root).id.value,
                root.path.toString(),
                Long.MAX_VALUE,
            )
        if (
            DaemonManagementProtocol.json
                .encodeToString(DaemonManagementResponse.serializer(), maximumReply)
                .toByteArray(Charsets.UTF_8)
                .size > DaemonManagementProtocol.maximumBytes
        )
            return reject(DaemonManagementFailure.CAPACITY_EXCEEDED)
        return when (val registered = enroll(root)) {
            is Refinement.Rejected ->
                DaemonManagementResponse.Rejected(DaemonManagementRejection.Enrollment(registered.failure))
            is Refinement.Refined ->
                with(registered.value) {
                    DaemonManagementResponse.Registered(
                        target,
                        workspace.id.value,
                        workspace.root.path.toString(),
                        revision.value,
                    )
                }
        }
    }

    private fun reject(failure: DaemonManagementFailure) =
        DaemonManagementResponse.Rejected(DaemonManagementRejection.Protocol(failure))
}
