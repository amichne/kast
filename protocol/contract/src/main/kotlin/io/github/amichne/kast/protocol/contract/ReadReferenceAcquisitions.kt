package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

/** Informational correspondence from an old handle to freshly issued authority; never authorizes a write. */
@Serializable data class ReadReferenceAcquisition(val previous: ProtocolText, val current: ProtocolText)

@ConsistentCopyVisibility
@Serializable
data class ReadReferenceAcquisitions private constructor(val references: List<ReadReferenceAcquisition>) {
    init {
        require(references.size in 1..MAXIMUM && references.map { it.previous }.distinct().size == references.size)
    }

    companion object {
        const val MAXIMUM = 256

        fun admit(
            references: List<ReadReferenceAcquisition>
        ): Refinement<ReadReferenceAcquisitions, ReadReferenceAcquisitionFailure> =
            if (references.size !in 1..MAXIMUM || references.map { it.previous }.distinct().size != references.size)
                Refinement.Rejected(ReadReferenceAcquisitionFailure.INVALID_REFERENCES)
            else Refinement.Refined(ReadReferenceAcquisitions(references.toList()))
    }
}

enum class ReadReferenceAcquisitionFailure {
    INVALID_REFERENCES
}
