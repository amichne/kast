package io.github.amichne.kast.fixtureprobe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class ProbeUnloadStage {
    CHECK,
    EFFECT,
}

@Serializable
internal sealed interface ProbeUnloadObservation {
    @Serializable @SerialName("started") data class Started(val stage: ProbeUnloadStage) : ProbeUnloadObservation

    @Serializable @SerialName("completed") data class Completed(val stage: ProbeUnloadStage) : ProbeUnloadObservation

    @Serializable
    @SerialName("rejected")
    data class Rejected(val stage: ProbeUnloadStage, val failure: ProbeFailure) : ProbeUnloadObservation
}

/** Keep preflight refusal distinct from an uncertain effect, with bounded stage evidence. */
internal fun completeProbePluginUnload(
    evidence: ProbeEvidence,
    check: () -> ProbeResult<Unit>,
    unload: () -> ProbeResult<Unit>,
    observe: (ProbeUnloadObservation) -> Unit,
): ProbeExecution {
    observe(ProbeUnloadObservation.Started(ProbeUnloadStage.CHECK))
    when (val checked = check()) {
        is ProbeResult.Accepted -> observe(ProbeUnloadObservation.Completed(ProbeUnloadStage.CHECK))
        is ProbeResult.Rejected -> {
            observe(ProbeUnloadObservation.Rejected(ProbeUnloadStage.CHECK, checked.failure))
            return ProbeExecution.Rejected(checked.failure)
        }
    }
    observe(ProbeUnloadObservation.Started(ProbeUnloadStage.EFFECT))
    return when (val effect = unload()) {
        is ProbeResult.Accepted -> {
            observe(ProbeUnloadObservation.Completed(ProbeUnloadStage.EFFECT))
            ProbeExecution.LifecycleCompleted(evidence, ProbePluginLifecycle.UNLOADED)
        }
        is ProbeResult.Rejected -> {
            observe(ProbeUnloadObservation.Rejected(ProbeUnloadStage.EFFECT, effect.failure))
            ProbeExecution.EffectUncertain(effect.failure)
        }
    }
}
