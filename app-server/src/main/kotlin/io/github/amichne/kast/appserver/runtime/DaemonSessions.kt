package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.AppServerAction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal interface DaemonSessions {
    val upgrades: DaemonUpgradeControl
        get() = DaemonUpgradeControl.Unavailable

    fun upgradeBlockers(): Set<UpgradeBlocker> = emptySet()

    fun inspectSessions(): DaemonSessionInspection

    fun controlSession(action: AppServerAction.Control): ControlResult
}

internal object UnavailableDaemonSessions : DaemonSessions {
    override fun inspectSessions(): DaemonSessionInspection = DaemonSessionInspection.Pending

    override fun controlSession(action: AppServerAction.Control): ControlResult =
        ControlResult.Rejected(ControlFailure.HOST_UNAVAILABLE)
}

@Serializable
internal sealed interface DaemonSessionInspection {
    @Serializable @SerialName("pending") data object Pending : DaemonSessionInspection

    @Serializable @SerialName("rejected") data object Rejected : DaemonSessionInspection

    @Serializable @SerialName("closed") data object Closed : DaemonSessionInspection

    @Serializable
    @SerialName("prepared")
    data class Prepared(
        val connections: List<DaemonConnectionDocument>,
        val tasks: List<DaemonTaskDocument>,
    ) : DaemonSessionInspection
}

@Serializable internal data class DaemonConnectionDocument(val id: String, val client: String)

@Serializable
internal data class DaemonTaskDocument(
    val threadId: String,
    val controller: String?,
    val controllerLease: String?,
    val activity: DaemonTaskActivity,
    val pendingRequests: Int,
)

@Serializable
internal enum class DaemonTaskActivity {
    IDLE,
    ACTIVE,
    RECONCILIATION_REQUIRED,
}

internal fun TaskSessionView.document() =
    DaemonTaskDocument(
        thread.value,
        controller?.value,
        lease?.value,
        when (activity) {
            TaskActivity.Idle -> DaemonTaskActivity.IDLE
            is TaskActivity.Active -> DaemonTaskActivity.ACTIVE
            is TaskActivity.ReconciliationRequired -> DaemonTaskActivity.RECONCILIATION_REQUIRED
        },
        pendingRequests,
    )
