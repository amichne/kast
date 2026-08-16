package io.github.amichne.kast.change.intellij

import io.github.amichne.kast.change.apply.MutationDurabilityBarrier
import io.github.amichne.kast.change.apply.MutationDurabilityResult
import io.github.amichne.kast.change.apply.SourceWriteFailure

internal data class IntellijAddFileInput(
    val sourcePath: String,
    val postimageText: String,
)

internal sealed interface IntellijAddFilePhysicalState {
    data object Absent : IntellijAddFilePhysicalState

    data class Present(
        val text: String,
    ) : IntellijAddFilePhysicalState

    data class Rejected(
        val failure: SourceWriteFailure,
    ) : IntellijAddFilePhysicalState
}

internal class IntellijStagedAddFile internal constructor(
    val postimageText: String,
)

internal sealed interface IntellijAddFileStageResult {
    data class Staged(
        val file: IntellijStagedAddFile,
    ) : IntellijAddFileStageResult

    data class Rejected(
        val failure: SourceWriteFailure,
    ) : IntellijAddFileStageResult
}

internal interface IntellijAddFileStagingSession {
    fun physicalState(): IntellijAddFilePhysicalState

    fun stage(postimageText: String): IntellijAddFileStageResult

    fun clearStage(staged: IntellijStagedAddFile): IntellijSessionStepResult

    fun save(staged: IntellijStagedAddFile): IntellijSessionStepResult

    fun observe(): IntellijPhysicalSourceObservation
}

/** Stages one absent-file postimage in memory before the applied-write durability barrier. */
internal class IntellijAddFileWriteProtocol {
    /**
     * Proof transition: `(IntellijAddFileInput, MutationDurabilityBarrier,
     * IntellijAddFileStagingSession) -> IntellijWriteProtocolResult`.
     *
     * Applied establishes that an absent exact target was staged without a physical write, made
     * recovery-durable, saved once, and observed with its exact postimage. Expected platform and
     * durability failure is closed by [IntellijWriteProtocolResult]. Live IntelliJ values remain
     * inside the supplied request-local session.
     */
    fun execute(
        input: IntellijAddFileInput,
        durability: MutationDurabilityBarrier,
        session: IntellijAddFileStagingSession,
    ): IntellijWriteProtocolResult {
        when (val state = session.physicalState()) {
            IntellijAddFilePhysicalState.Absent -> Unit
            is IntellijAddFilePhysicalState.Present -> return IntellijWriteProtocolResult
                .RejectedBeforeMutation(SourceWriteFailure.PREIMAGE_CHANGED)
            is IntellijAddFilePhysicalState.Rejected -> return IntellijWriteProtocolResult
                .RejectedBeforeMutation(state.failure)
        }
        val staged = when (val result = session.stage(input.postimageText)) {
            is IntellijAddFileStageResult.Staged -> result.file
            is IntellijAddFileStageResult.Rejected -> return IntellijWriteProtocolResult
                .RejectedBeforeMutation(result.failure)
        }
        when (val durable = durability.recordApplied()) {
            MutationDurabilityResult.Durable -> Unit
            is MutationDurabilityResult.Rejected -> return rejectAfterClear(
                session,
                staged,
                SourceWriteFailure.DURABILITY_REJECTED,
            )
        }
        when (val saved = session.save(staged)) {
            IntellijSessionStepResult.Completed -> Unit
            is IntellijSessionStepResult.Rejected -> return IntellijWriteProtocolResult
                .RecoveryRequired(saved.failure)
        }
        return when (val observed = session.observe()) {
            is IntellijPhysicalSourceObservation.Observed -> IntellijWriteProtocolResult.Applied(
                observed.bytes,
                observed.changedPaths,
            )
            is IntellijPhysicalSourceObservation.Rejected -> IntellijWriteProtocolResult
                .RecoveryRequired(observed.failure)
        }
    }

    private fun rejectAfterClear(
        session: IntellijAddFileStagingSession,
        staged: IntellijStagedAddFile,
        failure: SourceWriteFailure,
    ): IntellijWriteProtocolResult = when (session.clearStage(staged)) {
        IntellijSessionStepResult.Completed ->
            IntellijWriteProtocolResult.RejectedAfterRollback(failure)
        is IntellijSessionStepResult.Rejected ->
            IntellijWriteProtocolResult.RecoveryRequired(SourceWriteFailure.ROLLBACK_FAILED)
    }
}
