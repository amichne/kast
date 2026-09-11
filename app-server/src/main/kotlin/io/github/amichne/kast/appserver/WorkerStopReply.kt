package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
internal enum class WorkerStopStatus {
    STOPPED
}

@Serializable
internal data class WorkerStopReply(
    val status: WorkerStopStatus,
    val root: String,
    val installationId: String,
    val stateEpoch: String,
    val serviceGeneration: String,
    val configurationIdentity: String,
    val workspaceRevision: Long,
) {
    companion object {
        fun encode(result: InstalledWorkerStop): String =
            when (result) {
                is InstalledWorkerStop.Rejected ->
                    WorkerControlReply.encode(InstalledWorkerStart.Rejected(result.failure))
                is InstalledWorkerStop.Stopped ->
                    Json.encodeToString(
                        WorkerStopReply(
                            WorkerStopStatus.STOPPED,
                            result.root.toString(),
                            result.binding.installationId,
                            result.binding.stateEpoch.toString(),
                            result.binding.serviceGeneration.toString(),
                            result.binding.configurationIdentity,
                            result.binding.workspaceRevision,
                        )
                    )
            }

        fun decode(raw: String): InstalledWorkerStop =
            try {
                val document = Json.parseToJsonElement(raw).jsonObject
                if (document.keys == setOf("failure")) {
                    when (val failure = WorkerControlReply.decode(raw)) {
                        is InstalledWorkerStart.Rejected -> InstalledWorkerStop.Rejected(failure.failure)
                        is InstalledWorkerStart.Ready ->
                            InstalledWorkerStop.Rejected(WorkerControlFailure.INVALID_REQUEST)
                    }
                } else {
                    val value = Json.decodeFromString<WorkerStopReply>(raw)
                    val root = Path.of(value.root)
                    when (
                        val binding =
                            WorkerRouteBinding.Installation.admit(
                                value.installationId,
                                value.stateEpoch,
                                value.serviceGeneration,
                                value.configurationIdentity,
                                value.workspaceRevision,
                            )
                    ) {
                        is Refinement.Rejected -> InstalledWorkerStop.Rejected(binding.failure)
                        is Refinement.Refined ->
                            if (!root.isAbsolute || root.normalize() != root)
                                InstalledWorkerStop.Rejected(WorkerControlFailure.IDENTITY_REJECTED)
                            else InstalledWorkerStop.Stopped(root, binding.value)
                    }
                }
            } catch (_: Exception) {
                InstalledWorkerStop.Rejected(WorkerControlFailure.INVALID_REQUEST)
            }
    }
}
