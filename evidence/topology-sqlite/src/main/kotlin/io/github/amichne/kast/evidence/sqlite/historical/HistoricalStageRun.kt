package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalCaptureBasis
import java.sql.Connection
import java.util.concurrent.CancellationException

/** This owner keeps acknowledged commits outside the effect and cleanup scopes that can fail. */
internal class HistoricalStageRun(
    private val connection: Connection,
    private val basis: HistoricalCaptureBasis,
    private val admitted: HistoricalStageAdmission,
    private val selected: List<HistoricalStageFilePlan>,
) {
    private var committedFiles = 0L
    private var committedRows = 0L
    private var maximumBatchRows = 0L
    private var basisFactRowsExamined = selected.sumOf { it.basisFactRowsExamined }
    private var rolledBackBatches = 0L
    private var transaction = Transaction.SETTLED
    private var phase = HistoricalStagePhase.WRITE

    fun advance(control: HistoricalStageControl): Refinement<HistoricalStageAdvance, HistoricalStageFailure> {
        lateinit var cleanup: HistoricalStageCleanup
        val outcome =
            try {
                run(control)
            } finally {
                cleanup = settle()
            }
        val receipt = receipt()
        val progress = progress(receipt)
        return when (outcome) {
            is RunOutcome.Finished -> finish(outcome.signal, receipt, progress, cleanup)
            is RunOutcome.Failed -> failed(outcome.fault, progress, cleanup)
            is RunOutcome.Cancelled -> throw HistoricalStageCancellationException(progress, cleanup, outcome.cause)
        }
    }

    private fun run(control: HistoricalStageControl): RunOutcome =
        try {
            when (val effect = stageEffect { advanceFiles(control) }) {
                is HistoricalStageEffect.Completed -> RunOutcome.Finished(effect.value)
                is HistoricalStageEffect.Failed -> RunOutcome.Failed(HistoricalStageFault.Storage(phase, effect.cause))
            }
        } catch (cancelled: CancellationException) {
            RunOutcome.Cancelled(cancelled)
        }

    private fun advanceFiles(control: HistoricalStageControl): HistoricalStageSignal {
        for (file in selected) {
            val batch = file.materialize()
            maximumBatchRows = maxOf(maximumBatchRows, batch.rowCount)
            basisFactRowsExamined += batch.basisFactRowsExamined
            transaction = Transaction.UNCOMMITTED
            phase = HistoricalStagePhase.WRITE
            connection.writeHistoricalStageBatch(batch)
            phase = HistoricalStagePhase.CONTROL
            when (val signal = control.beforeCommit()) {
                HistoricalStageSignal.Continue -> {
                    phase = HistoricalStagePhase.COMMIT
                    transaction = Transaction.COMMIT_UNCERTAIN
                    connection.commit()
                    committedFiles++
                    committedRows += batch.rowCount
                    transaction = Transaction.SETTLED
                }
                is HistoricalStageSignal.Stop -> return signal
            }
        }
        return HistoricalStageSignal.Continue
    }

    private fun settle(): HistoricalStageCleanup {
        lateinit var close: HistoricalStageCleanupOutcome
        val rollback =
            try {
                stageEffect { connection.rollback() }.cleanup()
            } finally {
                close = stageEffect { connection.close() }.cleanup()
            }
        if (transaction == Transaction.UNCOMMITTED) {
            transaction =
                when (rollback) {
                    HistoricalStageCleanupOutcome.Completed -> {
                        rolledBackBatches++
                        Transaction.SETTLED
                    }
                    is HistoricalStageCleanupOutcome.Failed -> Transaction.ROLLBACK_UNCERTAIN
                }
        }
        return HistoricalStageCleanup(rollback, close)
    }

    private fun progress(receipt: HistoricalStageReceipt): HistoricalStageProgress =
        when (transaction) {
            Transaction.SETTLED -> HistoricalStageProgress.Settled(receipt)
            Transaction.COMMIT_UNCERTAIN ->
                HistoricalStageProgress.Uncertain(
                    receipt,
                    HistoricalStageTransactionUncertainty.COMMIT,
                )
            Transaction.UNCOMMITTED,
            Transaction.ROLLBACK_UNCERTAIN ->
                HistoricalStageProgress.Uncertain(
                    receipt,
                    HistoricalStageTransactionUncertainty.ROLLBACK,
                )
        }

    private fun finish(
        signal: HistoricalStageSignal,
        receipt: HistoricalStageReceipt,
        progress: HistoricalStageProgress,
        cleanup: HistoricalStageCleanup,
    ): Refinement<HistoricalStageAdvance, HistoricalStageFailure> =
        when (val rollback = cleanup.rollback) {
            is HistoricalStageCleanupOutcome.Failed ->
                cleanupFailed(
                    signal,
                    HistoricalStageFault.Storage(HistoricalStagePhase.ROLLBACK, rollback.cause),
                    progress,
                    cleanup,
                )
            HistoricalStageCleanupOutcome.Completed ->
                when (val close = cleanup.close) {
                    is HistoricalStageCleanupOutcome.Failed ->
                        cleanupFailed(
                            signal,
                            HistoricalStageFault.Storage(HistoricalStagePhase.CLOSE, close.cause),
                            progress,
                            cleanup,
                        )
                    HistoricalStageCleanupOutcome.Completed ->
                        Refinement.Refined(
                            when (signal) {
                                HistoricalStageSignal.Continue -> HistoricalStageAdvance.Advanced(receipt)
                                is HistoricalStageSignal.Stop -> HistoricalStageAdvance.Stopped(signal.cause, receipt)
                            }
                        )
                }
        }

    private fun cleanupFailed(
        signal: HistoricalStageSignal,
        fault: HistoricalStageFault.Storage,
        progress: HistoricalStageProgress,
        cleanup: HistoricalStageCleanup,
    ): Refinement<HistoricalStageAdvance, HistoricalStageFailure> =
        failed(
            when (signal) {
                HistoricalStageSignal.Continue -> fault
                is HistoricalStageSignal.Stop -> HistoricalStageFault.Stopped(signal.cause)
            },
            progress,
            cleanup,
        )

    private fun failed(
        fault: HistoricalStageFault,
        progress: HistoricalStageProgress,
        cleanup: HistoricalStageCleanup,
    ): Refinement<HistoricalStageAdvance, HistoricalStageFailure> =
        Refinement.Rejected(HistoricalStageFailure.Effect(fault, progress, cleanup))

    private fun receipt(): HistoricalStageReceipt =
        HistoricalStageReceipt(
            basis.snapshot.identity,
            if (admitted.completedFiles.size + committedFiles == basis.files.size.toLong())
                HistoricalStageCompletion.READY
            else HistoricalStageCompletion.PENDING,
            HistoricalStageWork(
                historicalCount(admitted.completedFiles.size.toLong()),
                historicalCount(admitted.reusedRows),
                historicalCount(committedFiles),
                historicalCount(committedRows),
                historicalCount(rolledBackBatches),
                historicalCount(maximumBatchRows),
                historicalCount(admitted.examinedRows),
                historicalCount(basis.snapshot.historicalRowCount()),
                historicalCount(admitted.basisFactRowsExamined + basisFactRowsExamined),
            ),
        )

    private enum class Transaction {
        SETTLED,
        UNCOMMITTED,
        COMMIT_UNCERTAIN,
        ROLLBACK_UNCERTAIN,
    }

    private sealed interface RunOutcome {
        data class Finished(val signal: HistoricalStageSignal) : RunOutcome

        data class Failed(val fault: HistoricalStageFault.Storage) : RunOutcome

        data class Cancelled(val cause: CancellationException) : RunOutcome
    }
}
