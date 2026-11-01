package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val hostRefreshJson = Json { encodeDefaults = true }

internal fun applicationHostRefreshReply(
    command: IdeLifecycleCommand.RefreshHostBundle,
    settlement: HostedApplicationSettlement,
    scope: CoroutineScope,
): LifecycleReply =
    hostRefreshReply(
        command,
        settlement,
        launch = { action ->
            scope.launch(Dispatchers.IO) { action() }
            Unit
        },
        observe = ::recordHostRefresh,
    )

internal fun recordHostRefresh(observation: HostRefreshObservation) {
    Logger.getInstance(HostBundleRefreshAdapter::class.java)
        .info("kast_host_refresh " + hostRefreshJson.encodeToString(HostRefreshObservation.serializer(), observation))
}

@Serializable
internal data class HostRefreshMarkerSnapshot(
    val cachedTimestampMillis: Long,
    val physicalTimestampMillis: Long,
    val cachedLength: Long,
    val physicalLength: Long,
)

@Serializable
internal enum class HostRefreshStage {
    CACHE_PREPARATION,
    MARKER_COMPARISON,
    CONTENT_EVENTS,
    ADMISSION,
    DISPATCH,
    COMPLETION,
}

@Serializable
internal sealed interface HostRefreshOutcome {
    @Serializable @SerialName("ADMITTED") data object Admitted : HostRefreshOutcome

    @Serializable @SerialName("STARTED") data object Started : HostRefreshOutcome

    @Serializable @SerialName("SUBMITTED") data object Submitted : HostRefreshOutcome

    @Serializable @SerialName("COMPLETED") data object Completed : HostRefreshOutcome

    @Serializable
    @SerialName("MARKER_PREPARED")
    data class MarkerPrepared(val marker: HostRefreshMarkerSnapshot) : HostRefreshOutcome

    @Serializable
    @SerialName("MARKER_COMPARED")
    data class MarkerCompared(val marker: HostRefreshMarkerSnapshot) : HostRefreshOutcome

    @Serializable
    @SerialName("CONTENT_EVENTS")
    data class ContentEvents(val before: Int, val after: Int, val marker: HostRefreshMarkerSnapshot) :
        HostRefreshOutcome

    @Serializable @SerialName("REJECTED") data class Rejected(val reason: IdeLifecycleFailure) : HostRefreshOutcome
}

@Serializable
internal data class HostRefreshObservation(
    val stage: HostRefreshStage,
    val outcome: HostRefreshOutcome,
)

internal fun hostRefreshReply(
    command: IdeLifecycleCommand.RefreshHostBundle,
    settlement: HostedApplicationSettlement,
    launch: (() -> Unit) -> Unit,
    observe: (HostRefreshObservation) -> Unit,
): LifecycleReply {
    fun record(stage: HostRefreshStage, outcome: HostRefreshOutcome) = observe(HostRefreshObservation(stage, outcome))
    fun rejected(reason: IdeLifecycleFailure): LifecycleReply {
        record(HostRefreshStage.ADMISSION, HostRefreshOutcome.Rejected(reason))
        return LifecycleReply.immediate(IdeLifecycleResult.Blocked(reason))
    }
    val ticket =
        when (val admitted = settlement.admitRefresh(command)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(admitted.failure)
        }
    record(HostRefreshStage.ADMISSION, HostRefreshOutcome.Admitted)
    return LifecycleReply.afterAcknowledgment(ticket.acknowledgment) {
        launch {
            when (
                val submitted =
                    settlement.dispatchRefresh(ticket) {
                        record(HostRefreshStage.DISPATCH, HostRefreshOutcome.Started)
                        ticket.root.submit { record(HostRefreshStage.COMPLETION, HostRefreshOutcome.Completed) }
                    }
            ) {
                HostRefreshSubmission.Submitted -> record(HostRefreshStage.DISPATCH, HostRefreshOutcome.Submitted)
                is HostRefreshSubmission.Rejected ->
                    record(HostRefreshStage.DISPATCH, HostRefreshOutcome.Rejected(submitted.reason))
            }
        }
    }
}
