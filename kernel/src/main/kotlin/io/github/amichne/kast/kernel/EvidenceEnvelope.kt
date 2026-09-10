package io.github.amichne.kast.kernel

import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.UUID

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

/** Detached evidence of the content view used by a live IDE read. */
enum class LiveReadContentView { SAVED_PSI_COMMITTED }

enum class LiveReadEvidenceFailure { INVALID_ROOT, INVALID_EPOCH, UNSUPPORTED_VERSION }

/** Detached provenance only. This value cannot acquire or restore live execution authority. */
data class LiveReadEvidence private constructor(
    val workspaceRoot: String,
    val host: UUID,
    val epoch: Long,
    val contentView: LiveReadContentView,
    val version: Int,
) {
    companion object {
        const val VERSION = 1

        fun create(
            workspaceRoot: String,
            host: UUID,
            epoch: Long,
            contentView: LiveReadContentView,
            version: Int,
        ): Refinement<LiveReadEvidence, LiveReadEvidenceFailure> {
            val root = try { Path.of(workspaceRoot) } catch (_: InvalidPathException) {
                return Refinement.Rejected(LiveReadEvidenceFailure.INVALID_ROOT)
            }
            return when {
                !root.isAbsolute || root.normalize() != root ->
                    Refinement.Rejected(LiveReadEvidenceFailure.INVALID_ROOT)
                epoch <= 0 -> Refinement.Rejected(LiveReadEvidenceFailure.INVALID_EPOCH)
                version != VERSION -> Refinement.Rejected(LiveReadEvidenceFailure.UNSUPPORTED_VERSION)
                else -> Refinement.Refined(LiveReadEvidence(workspaceRoot, host, epoch, contentView, version))
            }
        }
    }
}

/** A live read and a canonical publication are distinct claims. */
sealed interface EvidenceBasis {
    data class Published(val generation: EvidenceGeneration) : EvidenceBasis
    data class Live(val evidence: LiveReadEvidence) : EvidenceBasis
}

/** A successful semantic payload bound to its operation and exact evidence basis. */
data class EvidenceEnvelope<out Payload>(
    val operation: OperationId,
    val basis: EvidenceBasis,
    val payload: Payload,
) {
    /** Existing published producers retain their strong generation-only construction boundary. */
    constructor(operation: OperationId, generation: EvidenceGeneration, payload: Payload) :
        this(operation, EvidenceBasis.Published(generation), payload)
}
