package io.github.amichne.kast.appserver.ide

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal enum class HostedAdmissionStage {
    ENTRY_FAMILY,
    DESCRIPTOR_OWNER,
    OWNER_LIVENESS,
    CURRENT_DESCRIPTOR,
    ROOT_ADMISSION,
    LIVE_ADMISSION,
}

@Serializable
internal sealed interface HostedAdmissionEvidence {
    @Serializable
    @SerialName("HOST_ADMISSION_EXCLUDED")
    data class Excluded(val stage: HostedAdmissionStage) : HostedAdmissionEvidence

    @Serializable
    @SerialName("HOST_ADMISSION_SELECTED")
    data class Selected(val stage: HostedAdmissionStage) : HostedAdmissionEvidence

    @Serializable
    @SerialName("HOST_ADMISSION_REJECTED")
    data class Rejected(val stage: HostedAdmissionStage, val failure: ExistingIdeFailure) : HostedAdmissionEvidence
}

internal fun interface HostedAdmissionObserver {
    fun record(evidence: HostedAdmissionEvidence)
}

/** Each closed stage/outcome/failure tuple is emitted at most once, independent of directory cardinality. */
internal class BoundedHostedAdmissionObserver(private val delegate: HostedAdmissionObserver) : HostedAdmissionObserver {
    private val emitted = HashSet<HostedAdmissionEvidence>()

    override fun record(evidence: HostedAdmissionEvidence) {
        if (emitted.add(evidence)) delegate.record(evidence)
    }
}

internal object JsonLineHostedAdmissionObserver : HostedAdmissionObserver {
    private val json = Json { encodeDefaults = true }

    override fun record(evidence: HostedAdmissionEvidence) {
        System.err.println(json.encodeToString(HostedAdmissionEvidence.serializer(), evidence))
    }
}
