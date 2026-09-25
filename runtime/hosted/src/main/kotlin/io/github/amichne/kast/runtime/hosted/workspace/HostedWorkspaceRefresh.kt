package io.github.amichne.kast.runtime.hosted.workspace

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshCommand
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure as PublicFailure
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshResponse
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshRule
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshStage as PublicStage
import io.github.amichne.kast.runtime.hosted.HostedVfsRefreshOutcome
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The refresh port owns native effects; read admission may request only its file comparison. */
internal class HostedWorkspaceRefresh(
    project: Project,
    private val root: CanonicalWorkspaceRoot,
    private val query: HostedQueryService,
    scope: CoroutineScope,
) : Disposable {
    private val port = IntellijWorkspaceRefreshPort(project, root, query)
    private val service = WorkspaceRefreshService(port)
    private val triggers = WorkspaceRefreshTaskTrigger(project, root, scope) { command -> execute(command) }

    fun refreshForRead(complete: (HostedVfsRefreshOutcome) -> Unit) = port.refreshForRead(complete)

    fun initialImport(requestId: String): WorkspaceRefreshResult =
        when (val prepared = port.prepareInitialLink()) {
            is Refinement.Rejected -> WorkspaceRefreshResult.Rejected(prepared.failure)
            is Refinement.Refined ->
                admittedRequest(
                    WorkspaceRefreshCommand.Request(
                        requestId,
                        io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD,
                    )
                )
        }

    fun execute(command: WorkspaceRefreshCommand): WorkspaceRefreshResponse {
        val result =
            when (command) {
                is WorkspaceRefreshCommand.Configure -> configure(command.rule)
                is WorkspaceRefreshCommand.Request -> admittedRequest(command)
                is WorkspaceRefreshCommand.Status -> withId(command.requestId) { service.status(it) }
            }
        val response = WorkspaceRefreshResponse(root.value, query.hostLifetime.value.toString(), result)
        // Bounded identity, stage and finite outcome only; never task text, source or native diagnostics.
        Logger.getInstance(HostedWorkspaceRefresh::class.java)
            .info("kast_workspace_refresh " + Json.encodeToString(result.observation()))
        return response
    }

    private fun configure(rule: WorkspaceRefreshRule): WorkspaceRefreshResult =
        when (val admission = port.admission()) {
            is Refinement.Rejected -> WorkspaceRefreshResult.Rejected(admission.failure)
            is Refinement.Refined -> triggers.configure(rule)
        }

    private fun admittedRequest(command: WorkspaceRefreshCommand.Request): WorkspaceRefreshResult {
        val id =
            when (val parsed = WorkspaceRefreshRequestId.parse(command.requestId)) {
                is Refinement.Rejected -> return WorkspaceRefreshResult.Rejected(PublicFailure.INVALID_REQUEST)
                is Refinement.Refined -> parsed.value
            }
        if (service.contains(id)) return withId(command.requestId) { service.submit(it, command.effect) }
        return when (val admission = port.admission()) {
            is Refinement.Rejected -> WorkspaceRefreshResult.Rejected(admission.failure)
            is Refinement.Refined -> withId(command.requestId) { service.submit(it, command.effect) }
        }
    }

    private fun withId(
        raw: String,
        operation: (WorkspaceRefreshRequestId) -> WorkspaceRefreshStatus,
    ): WorkspaceRefreshResult =
        when (val id = WorkspaceRefreshRequestId.parse(raw)) {
            is Refinement.Rejected -> WorkspaceRefreshResult.Rejected(PublicFailure.INVALID_REQUEST)
            is Refinement.Refined ->
                when (val status = operation(id.value)) {
                    WorkspaceRefreshStatus.Complete -> WorkspaceRefreshResult.Complete(raw)
                    is WorkspaceRefreshStatus.Pending ->
                        WorkspaceRefreshResult.Pending(
                            raw,
                            when (status.stage) {
                                WorkspaceRefreshStage.QUEUED -> PublicStage.QUEUED
                                WorkspaceRefreshStage.EFFECT -> PublicStage.EFFECT
                                WorkspaceRefreshStage.ADMISSION -> PublicStage.ADMISSION
                            },
                        )
                    is WorkspaceRefreshStatus.Failed ->
                        WorkspaceRefreshResult.Failed(
                            raw,
                            when (status.reason) {
                                WorkspaceRefreshFailure.BUSY -> PublicFailure.NEWER_CHANGE
                                WorkspaceRefreshFailure.UNSAVED_DOCUMENTS -> PublicFailure.UNSAVED_DOCUMENTS
                                WorkspaceRefreshFailure.UNLINKED_BUILD -> PublicFailure.UNLINKED_BUILD
                                WorkspaceRefreshFailure.EFFECT_FAILED -> PublicFailure.EFFECT_FAILED
                                WorkspaceRefreshFailure.CANCELLED -> PublicFailure.CANCELLED
                                WorkspaceRefreshFailure.DISPOSED -> PublicFailure.DISPOSED
                                WorkspaceRefreshFailure.DEADLINE_EXCEEDED -> PublicFailure.DEADLINE_EXCEEDED
                            },
                        )
                    is WorkspaceRefreshStatus.Rejected ->
                        WorkspaceRefreshResult.Rejected(
                            when (status.reason) {
                                WorkspaceRefreshRejection.INVALID_REQUEST -> PublicFailure.INVALID_REQUEST
                                WorkspaceRefreshRejection.REQUEST_CONFLICT -> PublicFailure.REQUEST_CONFLICT
                                WorkspaceRefreshRejection.CAPACITY -> PublicFailure.CAPACITY
                                WorkspaceRefreshRejection.UNKNOWN_REQUEST -> PublicFailure.UNKNOWN_REQUEST
                                WorkspaceRefreshRejection.DISPOSED -> PublicFailure.DISPOSED
                            }
                        )
                }
        }

    fun hasWork(): Boolean = service.hasWork()

    override fun dispose() {
        triggers.dispose()
        service.dispose()
    }
}

@kotlinx.serialization.Serializable
private enum class RefreshObservation {
    PENDING,
    COMPLETE,
    FAILED,
    REJECTED,
    CONFIGURED,
}

private fun WorkspaceRefreshResult.observation(): RefreshObservation =
    when (this) {
        is WorkspaceRefreshResult.Pending -> RefreshObservation.PENDING
        is WorkspaceRefreshResult.Complete -> RefreshObservation.COMPLETE
        is WorkspaceRefreshResult.Failed -> RefreshObservation.FAILED
        is WorkspaceRefreshResult.Rejected -> RefreshObservation.REJECTED
        is WorkspaceRefreshResult.Configured -> RefreshObservation.CONFIGURED
    }
