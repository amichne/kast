package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.AppServerAction
import io.github.amichne.kast.appserver.AppServerControlAdmission
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

/** Retirement closes admission only while the installation lifecycle fence is observed. */
internal enum class DaemonRetirementAdmission {
    ACCEPTED,
    UNFENCED,
}

/** The existing coordinator owns lifecycle admission; enrollment owns filesystem admission and atomic writes. */
internal class DaemonManagement(
    private val target: DaemonManagementTarget,
    private val available: () -> Boolean,
    private val status: () -> CoordinatorStatusDocument,
    private val sessions: DaemonSessions,
    private val preparation: DaemonWorkspacePreparation = DaemonWorkspacePreparation.Unavailable,
    private val projection: () -> io.github.amichne.kast.appserver.ManagementRuntimeProjection? = { null },
    private val retire: (() -> DaemonRetirementAdmission)? = null,
    private val enroll:
        (CanonicalBrokerDirectory) -> Refinement<WorkspaceRegistrationAcknowledgement, EnrollmentFailure>,
) {
    fun exchange(raw: String): String = respond(raw).text

    fun respond(raw: String): ManagementReply {
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
        return if (encoded.toByteArray(Charsets.UTF_8).size <= DaemonManagementProtocol.maximumBytes)
            ManagementReply(encoded, response is DaemonManagementResponse.Retiring)
        else
            ManagementReply(
                DaemonManagementProtocol.json.encodeToString(
                    DaemonManagementResponse.serializer(),
                    reject(DaemonManagementFailure.CAPACITY_EXCEEDED),
                ),
                false,
            )
    }

    internal fun execute(request: DaemonManagementRequest): DaemonManagementResponse {
        if (request.version != DaemonManagementProtocol.version)
            return reject(DaemonManagementFailure.UNSUPPORTED_VERSION)
        // The installation fence already closed ordinary admission; retirement is admitted only through it.
        if (request is DaemonManagementRequest.Retire) return retirement(request)
        if (!available()) return reject(DaemonManagementFailure.LIFECYCLE_TRANSITION)
        return executeAvailable(request)
    }

    private fun executeAvailable(request: DaemonManagementRequest): DaemonManagementResponse =
        when (request) {
            is DaemonManagementRequest.Status -> DaemonManagementResponse.Status(status(), projection())
            is DaemonManagementRequest.RegisterWorkspace -> manage { register(request) }
            is DaemonManagementRequest.Sessions ->
                if (request.target != target) reject(DaemonManagementFailure.IDENTITY_REJECTED)
                else DaemonManagementResponse.Sessions(target, sessions.inspectSessions())
            is DaemonManagementRequest.Control -> manage { control(request) }
            is DaemonManagementRequest.PrepareUpdate -> prepareUpdate(request)
            is DaemonManagementRequest.UpdateStatus ->
                update(request.target, request.requestId, sessions.upgrades::observe)
            is DaemonManagementRequest.CancelUpdate ->
                update(request.target, request.requestId, sessions.upgrades::cancel)
            is DaemonManagementRequest.CommitUpdate ->
                update(request.target, request.requestId, sessions.upgrades::commit)
            is DaemonManagementRequest.PrepareWorkspace ->
                if (request.target != target) reject(DaemonManagementFailure.IDENTITY_REJECTED)
                else manage { preparation.prepare(request.root).response(target) }
            is DaemonManagementRequest.WorkspacePreparationStatus ->
                if (request.target != target) reject(DaemonManagementFailure.IDENTITY_REJECTED)
                else preparation.observe(request.requestId).response(target)
            is DaemonManagementRequest.Retire -> retirement(request)
        }

    private fun retirement(request: DaemonManagementRequest.Retire): DaemonManagementResponse {
        if (request.serviceGeneration != target.serviceGeneration)
            return reject(DaemonManagementFailure.IDENTITY_REJECTED)
        val admit = retire ?: return reject(DaemonManagementFailure.UNAVAILABLE)
        return when (admit()) {
            DaemonRetirementAdmission.ACCEPTED -> DaemonManagementResponse.Retiring(target)
            DaemonRetirementAdmission.UNFENCED -> reject(DaemonManagementFailure.LIFECYCLE_UNFENCED)
        }
    }

    private fun prepareUpdate(request: DaemonManagementRequest.PrepareUpdate): DaemonManagementResponse {
        if (request.target != target) return reject(DaemonManagementFailure.IDENTITY_REJECTED)
        return when (val admitted = UpgradeCandidate.admit(request.candidate)) {
            is Refinement.Rejected ->
                DaemonManagementResponse.Rejected(DaemonManagementRejection.Upgrade(admitted.failure))
            is Refinement.Refined ->
                sessions.upgrades
                    .prepare(admitted.value) { sessions.upgradeBlockers() + preparation.upgradeBlockers() }
                    .response()
        }
    }

    private fun update(
        requestedTarget: DaemonManagementTarget,
        requestId: String,
        action: (UpgradeRequestId) -> Refinement<UpgradeStatus.Requested, DaemonUpgradeFailure>,
    ): DaemonManagementResponse {
        if (requestedTarget != target) return reject(DaemonManagementFailure.IDENTITY_REJECTED)
        return when (val admitted = UpgradeRequestId.admit(requestId)) {
            is Refinement.Rejected ->
                DaemonManagementResponse.Rejected(DaemonManagementRejection.Upgrade(admitted.failure))
            is Refinement.Refined -> action(admitted.value).response()
        }
    }

    private fun Refinement<UpgradeStatus.Requested, DaemonUpgradeFailure>.response(): DaemonManagementResponse =
        when (this) {
            is Refinement.Refined -> DaemonManagementResponse.Update(target, value.document())
            is Refinement.Rejected -> DaemonManagementResponse.Rejected(DaemonManagementRejection.Upgrade(failure))
        }

    private fun manage(action: () -> DaemonManagementResponse): DaemonManagementResponse =
        when (val result = sessions.upgrades.manage(action)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected ->
                DaemonManagementResponse.Rejected(DaemonManagementRejection.Upgrade(result.failure))
        }

    private fun control(request: DaemonManagementRequest.Control): DaemonManagementResponse {
        if (request.target != target) return reject(DaemonManagementFailure.IDENTITY_REJECTED)
        val action =
            when (
                val admitted = AppServerAction.Control.admit(request.operation, request.threadId, request.connectionId)
            ) {
                is AppServerControlAdmission.Admitted -> admitted.action
                is AppServerControlAdmission.Rejected ->
                    return DaemonManagementResponse.Rejected(
                        DaemonManagementRejection.Control(ControlFailure.INVALID_ID)
                    )
            }
        return when (val result = sessions.controlSession(action)) {
            is ControlResult.Rejected ->
                DaemonManagementResponse.Rejected(DaemonManagementRejection.Control(result.failure))
            ControlResult.Accepted ->
                DaemonManagementResponse.Controlled(
                    target,
                    action.operation,
                    action.target.threadId,
                    action.target.connectionId,
                )
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

internal data class ManagementReply(val text: String, val requestsRetirement: Boolean)
