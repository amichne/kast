package io.github.amichne.kast.change.recovery

import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.evidence.contract.RecoveryPreimage
import io.github.amichne.kast.evidence.contract.RecoverySourcePath
import io.github.amichne.kast.kernel.Refinement

/** Physical recovery observation must include both saved bytes and any loaded IDE document. */
sealed interface RecoveryDocumentObservation {
    data object NotLoaded : RecoveryDocumentObservation

    data class SavedAndCommitted(val content: RecoveryPreimage) : RecoveryDocumentObservation

    data object DirtyOrUncommitted : RecoveryDocumentObservation

    data object Unavailable : RecoveryDocumentObservation
}

data class RecoverySourceObservation(
    val source: RecoverySourcePath,
    val savedContent: RecoveryPreimage,
    val document: RecoveryDocumentObservation,
)

enum class RecoveryPreWriteObservationFailure {
    UNAVAILABLE,
    WRITE_SET_MISMATCH,
    AMBIGUOUS_PREIMAGE,
    SAVED_CONTENT_DIVERGED,
    DOCUMENT_CONTENT_DIVERGED,
    DOCUMENT_NOT_READY,
}

/** Historical exact-image proof. It carries no permission to write or reuse an old plan. */
class ConfirmedRecoveryPreimage private constructor(val record: MutationRecoveryRecord.PreWriteDurable) {
    companion object {
        fun admit(
            record: MutationRecoveryRecord.PreWriteDurable,
            sources: List<RecoverySourceObservation>,
        ): Refinement<ConfirmedRecoveryPreimage, RecoveryPreWriteObservationFailure> {
            val expected = record.preparation.plannedWrites
            // Legacy records encode absence as empty content. They cannot establish whether an
            // existing empty file or an absent file was planned; do not reinterpret that marker.
            if (expected.any { it.preimage.encodedContent.value.isEmpty() }) {
                return Refinement.Rejected(RecoveryPreWriteObservationFailure.AMBIGUOUS_PREIMAGE)
            }
            if (
                sources.size != expected.size || sources.map { it.source }.toSet() != expected.map { it.source }.toSet()
            ) {
                return Refinement.Rejected(RecoveryPreWriteObservationFailure.WRITE_SET_MISMATCH)
            }
            for (write in expected) {
                val observed = sources.single { it.source == write.source }
                if (observed.savedContent != write.preimage) {
                    return Refinement.Rejected(RecoveryPreWriteObservationFailure.SAVED_CONTENT_DIVERGED)
                }
                when (val document = observed.document) {
                    RecoveryDocumentObservation.NotLoaded -> Unit
                    is RecoveryDocumentObservation.SavedAndCommitted ->
                        if (document.content != write.preimage) {
                            return Refinement.Rejected(RecoveryPreWriteObservationFailure.DOCUMENT_CONTENT_DIVERGED)
                        }
                    RecoveryDocumentObservation.DirtyOrUncommitted,
                    RecoveryDocumentObservation.Unavailable ->
                        return Refinement.Rejected(RecoveryPreWriteObservationFailure.DOCUMENT_NOT_READY)
                }
            }
            return Refinement.Refined(ConfirmedRecoveryPreimage(record))
        }
    }
}

sealed interface RecoveryPreWriteObservation {
    data class Confirmed(val preimage: ConfirmedRecoveryPreimage) : RecoveryPreWriteObservation

    data class Rejected(val failure: RecoveryPreWriteObservationFailure) : RecoveryPreWriteObservation
}

/** Supplied by a freshly admitted project; persistence itself has no source-observation authority. */
fun interface RecoveryPreWriteObservationPort {
    fun observe(record: MutationRecoveryRecord.PreWriteDurable): RecoveryPreWriteObservation

    companion object {
        val Unavailable = RecoveryPreWriteObservationPort {
            RecoveryPreWriteObservation.Rejected(RecoveryPreWriteObservationFailure.UNAVAILABLE)
        }
    }
}

data class ExpectedRecoveryPostimage(val source: RecoverySourcePath, val content: RecoveryPreimage)

/** An observed exact postimage can strengthen an interrupted pre-write record without replaying the mutation. */
class ConfirmedRecoveryPostimage private constructor(val record: MutationRecoveryRecord.PreWriteDurable) {
    companion object {
        fun admit(
            record: MutationRecoveryRecord.PreWriteDurable,
            expected: List<ExpectedRecoveryPostimage>,
            sources: List<RecoverySourceObservation>,
        ): Refinement<ConfirmedRecoveryPostimage, RecoveryPreWriteObservationFailure> {
            val paths = record.preparation.plannedWrites.map { it.source }.toSet()
            if (sources.size != paths.size || expected.size != paths.size)
                return Refinement.Rejected(RecoveryPreWriteObservationFailure.WRITE_SET_MISMATCH)
            if (sources.map { it.source }.toSet() != paths || expected.map { it.source }.toSet() != paths) {
                return Refinement.Rejected(RecoveryPreWriteObservationFailure.WRITE_SET_MISMATCH)
            }
            for (image in expected) {
                val observed = sources.single { it.source == image.source }
                if (observed.savedContent != image.content)
                    return Refinement.Rejected(RecoveryPreWriteObservationFailure.SAVED_CONTENT_DIVERGED)
                when (val document = observed.document) {
                    RecoveryDocumentObservation.NotLoaded -> Unit
                    is RecoveryDocumentObservation.SavedAndCommitted ->
                        if (document.content != image.content) {
                            return Refinement.Rejected(RecoveryPreWriteObservationFailure.DOCUMENT_CONTENT_DIVERGED)
                        }
                    RecoveryDocumentObservation.DirtyOrUncommitted,
                    RecoveryDocumentObservation.Unavailable ->
                        return Refinement.Rejected(RecoveryPreWriteObservationFailure.DOCUMENT_NOT_READY)
                }
            }
            return Refinement.Refined(ConfirmedRecoveryPostimage(record))
        }
    }
}
