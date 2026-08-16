package io.github.amichne.kast.change.intellij

import io.github.amichne.kast.change.apply.MutationDurabilityBarrier
import io.github.amichne.kast.change.apply.MutationDurabilityResult
import io.github.amichne.kast.change.apply.SourceWriteFailure

/** Exact raw values already extracted from one [io.github.amichne.kast.change.apply.MutationAuthority]. */
internal data class IntellijMutationInput(
    val sourcePath: String,
    val preimageText: String,
    val postimageText: String,
    val mutations: List<IntellijTextMutation>,
)

internal data class IntellijTextMutation(
    val startInclusive: Int,
    val endExclusive: Int,
    val replacement: String,
)

internal sealed interface IntellijSessionStepResult {
    data object Completed : IntellijSessionStepResult

    data class Rejected(
        val failure: SourceWriteFailure,
    ) : IntellijSessionStepResult
}

internal sealed interface IntellijPhysicalSourceObservation {
    data class Observed(
        val bytes: ByteArray,
        val changedPaths: Set<String>,
    ) : IntellijPhysicalSourceObservation

    data class Rejected(
        val failure: SourceWriteFailure,
    ) : IntellijPhysicalSourceObservation
}

/** Request-local document capability; implementations retain no IntelliJ value after execution. */
internal interface IntellijDocumentMutationSession {
    fun currentText(): String

    fun mutate(input: IntellijMutationInput): IntellijSessionStepResult

    fun restore(preimageText: String): IntellijSessionStepResult

    fun save(): IntellijSessionStepResult

    fun observe(): IntellijPhysicalSourceObservation
}

internal sealed interface IntellijWriteProtocolResult {
    data class Applied(
        val bytes: ByteArray,
        val changedPaths: Set<String>,
    ) : IntellijWriteProtocolResult

    data class RejectedBeforeMutation(
        val failure: SourceWriteFailure,
    ) : IntellijWriteProtocolResult

    data class RejectedAfterRollback(
        val failure: SourceWriteFailure,
    ) : IntellijWriteProtocolResult

    data class RecoveryRequired(
        val failure: SourceWriteFailure,
    ) : IntellijWriteProtocolResult
}

/** Deterministic ordering protocol around one request-local IntelliJ document session. */
internal class IntellijSourceWriteProtocol {
    /**
     * Proof transition: `(IntellijMutationInput, MutationDurabilityBarrier,
     * IntellijDocumentMutationSession) -> IntellijWriteProtocolResult`.
     *
     * Establishes that an exact in-memory postimage crosses applied-write durability before save,
     * or restores the exact in-memory preimage when durability rejects. Expected platform failure
     * is closed by [IntellijWriteProtocolResult]. Raw document text and bytes remain request-local
     * to this IntelliJ boundary.
     */
    fun execute(
        input: IntellijMutationInput,
        durability: MutationDurabilityBarrier,
        session: IntellijDocumentMutationSession,
    ): IntellijWriteProtocolResult {
        if (session.currentText() != input.preimageText) {
            return IntellijWriteProtocolResult.RejectedBeforeMutation(
                SourceWriteFailure.PREIMAGE_CHANGED,
            )
        }
        when (val mutated = session.mutate(input)) {
            IntellijSessionStepResult.Completed -> Unit
            is IntellijSessionStepResult.Rejected ->
                return rollback(session, input, mutated.failure)
        }
        if (session.currentText() != input.postimageText) {
            return rollback(session, input, SourceWriteFailure.MUTATION_FAILED)
        }
        when (durability.recordApplied()) {
            MutationDurabilityResult.Durable -> Unit
            is MutationDurabilityResult.Rejected ->
                return rollback(session, input, SourceWriteFailure.DURABILITY_REJECTED)
        }
        when (val saved = session.save()) {
            IntellijSessionStepResult.Completed -> Unit
            is IntellijSessionStepResult.Rejected ->
                return IntellijWriteProtocolResult.RecoveryRequired(saved.failure)
        }
        return when (val observed = session.observe()) {
            is IntellijPhysicalSourceObservation.Observed ->
                IntellijWriteProtocolResult.Applied(observed.bytes, observed.changedPaths)
            is IntellijPhysicalSourceObservation.Rejected ->
                IntellijWriteProtocolResult.RecoveryRequired(observed.failure)
        }
    }

    private fun rollback(
        session: IntellijDocumentMutationSession,
        input: IntellijMutationInput,
        failure: SourceWriteFailure,
    ): IntellijWriteProtocolResult = when (session.restore(input.preimageText)) {
        IntellijSessionStepResult.Completed ->
            IntellijWriteProtocolResult.RejectedAfterRollback(failure)
        is IntellijSessionStepResult.Rejected ->
            IntellijWriteProtocolResult.RecoveryRequired(SourceWriteFailure.ROLLBACK_FAILED)
    }
}
