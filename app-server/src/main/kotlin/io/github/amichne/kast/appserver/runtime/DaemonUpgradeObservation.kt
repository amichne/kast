package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class DaemonUpgradeStage {
    PREPARE,
    OBSERVE,
    CANCEL,
    COMMIT,
}

@Serializable
internal sealed interface DaemonUpgradeOutcome {
    @Serializable @SerialName("pending") data class Pending(val blockers: List<UpgradeBlocker>) : DaemonUpgradeOutcome

    @Serializable @SerialName("sealed") data object Sealed : DaemonUpgradeOutcome

    @Serializable @SerialName("committed") data object Committed : DaemonUpgradeOutcome

    @Serializable @SerialName("cancelled") data object Cancelled : DaemonUpgradeOutcome

    @Serializable @SerialName("rejected") data class Rejected(val failure: DaemonUpgradeFailure) : DaemonUpgradeOutcome
}

/** Finite upgrade evidence excludes candidate hashes, request IDs, paths and source payloads. */
@Serializable
internal data class DaemonUpgradeObservation(
    val stage: DaemonUpgradeStage,
    val outcome: DaemonUpgradeOutcome,
    val event: String = "kast_daemon_upgrade",
) {
    fun toJson(): String = observationJson.encodeToString(this)
}

internal fun interface DaemonUpgradeObserver {
    fun observe(observation: DaemonUpgradeObservation)

    companion object {
        val Stderr = DaemonUpgradeObserver { System.err.println(it.toJson()) }
    }
}

internal fun Refinement<UpgradeStatus.Requested, DaemonUpgradeFailure>.observation(
    stage: DaemonUpgradeStage
): DaemonUpgradeObservation =
    DaemonUpgradeObservation(
        stage,
        when (this) {
            is Refinement.Rejected -> DaemonUpgradeOutcome.Rejected(failure)
            is Refinement.Refined ->
                when (val state = value) {
                    is UpgradeStatus.Pending -> DaemonUpgradeOutcome.Pending(state.blockers.toList())
                    is UpgradeStatus.Sealed -> DaemonUpgradeOutcome.Sealed
                    is UpgradeStatus.Committed -> DaemonUpgradeOutcome.Committed
                    is UpgradeStatus.Cancelled -> DaemonUpgradeOutcome.Cancelled
                }
        },
    )

private val observationJson = Json { encodeDefaults = true }
