package io.github.amichne.kast.fixtureprobe

internal enum class ProbeImportState {
    NOT_OBSERVED,
    IMPORTING,
    IMPORT_FINISHED,
    FINALIZING,
    FINAL_TASKS_FINISHED,
    FAILED,
}

internal enum class ProbeSetupImportEvidence {
    FINAL_TASKS_OBSERVED,
    NOT_OBSERVED_PERSISTED_MODEL,
}

internal enum class ProbeSetupStatus {
    CANDIDATE,
    INDEXING,
    EXTERNAL_TASKS_ACTIVE,
    IMPORT_PENDING,
    IMPORT_FAILED,
    GRADLE_MODULE_UNAVAILABLE,
}

internal data class ProbeSetupGeneration(
    val roots: Long,
    val workspace: Long,
    val vfs: Long,
    val psi: Long,
    val dumb: Long,
)

internal data class ProbeSetupSample(
    val status: ProbeSetupStatus,
    val generation: ProbeSetupGeneration,
    val import: ProbeImportState,
)

internal class ProbeSetupObservation private constructor(val import: ProbeSetupImportEvidence) {
    companion object {
        fun admit(
            before: ProbeSetupSample,
            after: ProbeSetupSample,
            elapsedNanos: Long,
        ): ProbeResult<ProbeSetupObservation> {
            if (
                before != after || after.status != ProbeSetupStatus.CANDIDATE || elapsedNanos < SETUP_QUIET_WINDOW_NANOS
            ) {
                return ProbeResult.Rejected(ProbeFailure.SETUP_MOVING)
            }
            val evidence =
                when (after.import) {
                    ProbeImportState.FINAL_TASKS_FINISHED -> ProbeSetupImportEvidence.FINAL_TASKS_OBSERVED
                    ProbeImportState.NOT_OBSERVED -> ProbeSetupImportEvidence.NOT_OBSERVED_PERSISTED_MODEL
                    else -> return ProbeResult.Rejected(ProbeFailure.SETUP_IMPORT_PENDING)
                }
            return ProbeResult.Accepted(ProbeSetupObservation(evidence))
        }
    }
}

internal const val SETUP_QUIET_WINDOW_MILLIS = 2000L
internal const val SETUP_QUIET_WINDOW_NANOS = SETUP_QUIET_WINDOW_MILLIS * 1000000L
