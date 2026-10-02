package io.github.amichne.kast.distribution.cli

/** A version-matched readiness exchange and canonical service generation, still behind the tool fence. */
internal class ReadyReset private constructor(val staged: StagedReset, val generation: RuntimeServiceGeneration) {
    companion object {
        fun observe(staged: StagedReset, runtime: ForceDaemonRuntime): ResetReadiness {
            val ready = runtime.readiness(staged.payload.installation)
            if (ready !is ResetActivationObservation.Ready || ready.version != staged.payload.version.value)
                return ResetReadiness.Rejected(ForceResetFailure.READINESS_REJECTED)
            return when (val admitted = RuntimeServiceGeneration.admit(ready.generation)) {
                is RuntimeGenerationAdmission.Admitted -> ResetReadiness.Ready(ReadyReset(staged, admitted.generation))
                RuntimeGenerationAdmission.Rejected -> ResetReadiness.Rejected(ForceResetFailure.READINESS_REJECTED)
            }
        }
    }
}

internal sealed interface ResetReadiness {
    data class Ready(val reset: ReadyReset) : ResetReadiness

    data class Rejected(val failure: ForceResetFailure) : ResetReadiness
}
