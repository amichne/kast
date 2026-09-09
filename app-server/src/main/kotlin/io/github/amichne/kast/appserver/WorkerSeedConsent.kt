package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

@Serializable enum class WorkerSeedConsentSelection { PREGRANTED, INTERACTIVE }
@Serializable enum class WorkerSeedConsent { GRANTED, ABSENT }
@Serializable enum class WorkerSeedCategory { GLOBAL_VFS, GLOBAL_INDEXES, PROJECT_MODEL, CLASSPATH_METADATA }

/** Exact measured copy disclosure; the bounded category set never contains source file names or payloads. */
class WorkerSeedDisclosure private constructor(val categories: Set<WorkerSeedCategory>, val estimatedBytes: Long) {
    companion object {
        fun admit(categories: Set<WorkerSeedCategory>, estimatedBytes: Long): Refinement<WorkerSeedDisclosure,WorkerControlFailure> =
            if (categories.isNotEmpty() && estimatedBytes >= 0) Refinement.Refined(WorkerSeedDisclosure(categories.toSet(), estimatedBytes))
            else Refinement.Rejected(WorkerControlFailure.INVALID_REQUEST)
    }
}
fun interface WorkerSeedConsentAuthority {
    suspend fun request(disclosure: WorkerSeedDisclosure): WorkerSeedConsent
    data object Unavailable : WorkerSeedConsentAuthority {
        override suspend fun request(disclosure: WorkerSeedDisclosure) = WorkerSeedConsent.ABSENT
    }
}
