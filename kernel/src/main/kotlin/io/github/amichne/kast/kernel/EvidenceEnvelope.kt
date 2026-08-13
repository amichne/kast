package io.github.amichne.kast.kernel

enum class EvidenceGenerationFailure {
    NEGATIVE,
}

@JvmInline
value class EvidenceGeneration private constructor(
    val value: Long,
) : Comparable<EvidenceGeneration> {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<EvidenceGeneration, EvidenceGenerationFailure>`.
         *
         * Establishes a non-negative monotonically comparable evidence generation.
         * [EvidenceGenerationFailure] is the closed expected failure. Raw generation numbers may
         * be extracted only at the workspace-publication or external protocol boundary.
         */
        fun parse(raw: Long): Refinement<EvidenceGeneration, EvidenceGenerationFailure> =
            if (raw >= 0) Refinement.Refined(EvidenceGeneration(raw))
            else Refinement.Rejected(EvidenceGenerationFailure.NEGATIVE)
    }

    override fun compareTo(other: EvidenceGeneration): Int = value.compareTo(other.value)
}

/**
 * A successful semantic payload bound to the permanent operation that produced it and the
 * evidence generation against which it was proven.
 */
data class EvidenceEnvelope<out Payload>(
    val operation: OperationId,
    val generation: EvidenceGeneration,
    val payload: Payload,
)
