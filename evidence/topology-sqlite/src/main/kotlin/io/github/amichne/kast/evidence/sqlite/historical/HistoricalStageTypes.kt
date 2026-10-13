package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalDigest
import io.github.amichne.kast.topology.contract.historical.HistoricalReadCount

sealed interface HistoricalStageFailure {
    data class Storage(val cause: HistoricalStorageFailure) : HistoricalStageFailure

    data object InvalidBudget : HistoricalStageFailure

    data object BasisMismatch : HistoricalStageFailure

    data object ContentRejected : HistoricalStageFailure

    data object FileCapacityExceeded : HistoricalStageFailure

    data object Incomplete : HistoricalStageFailure
}

class HistoricalStageBudget private constructor(val files: Int, val rowsPerFile: Int) {
    companion object {
        const val MAXIMUM_FILES = 1000
        const val MAXIMUM_FILE_ROWS = 100000

        fun parse(files: Int, rowsPerFile: Int): Refinement<HistoricalStageBudget, HistoricalStageFailure> =
            if (files in 1..MAXIMUM_FILES && rowsPerFile in 1..MAXIMUM_FILE_ROWS)
                Refinement.Refined(HistoricalStageBudget(files, rowsPerFile))
            else Refinement.Rejected(HistoricalStageFailure.InvalidBudget)
    }
}

enum class HistoricalStageCompletion {
    PENDING,
    READY,
}

enum class HistoricalStageStopCause {
    INTERRUPTED,
    CANCELLED,
}

sealed interface HistoricalStageSignal {
    data object Continue : HistoricalStageSignal

    data class Stop(val cause: HistoricalStageStopCause) : HistoricalStageSignal
}

/**
 * The effect runs after a file's writes and before its commit. It can stop the transaction without partial progress.
 */
fun interface HistoricalStageControl {
    fun beforeCommit(): HistoricalStageSignal

    data object Uninterrupted : HistoricalStageControl {
        override fun beforeCommit(): HistoricalStageSignal =
            if (Thread.currentThread().isInterrupted) HistoricalStageSignal.Stop(HistoricalStageStopCause.INTERRUPTED)
            else HistoricalStageSignal.Continue
    }
}

/** Counts name logical rows and observed batch lists. The fresh basis remains a complete materialized capture. */
data class HistoricalStageWork(
    val filesReused: HistoricalReadCount,
    val rowsReused: HistoricalReadCount,
    val filesCommitted: HistoricalReadCount,
    val rowsCommitted: HistoricalReadCount,
    val rolledBackBatches: HistoricalReadCount,
    val maximumBatchRows: HistoricalReadCount,
    val verificationRows: HistoricalReadCount,
    val completeBasisRowsRetained: HistoricalReadCount,
    val basisFactRowsExamined: HistoricalReadCount,
)

data class HistoricalStageReceipt(
    val identity: HistoricalDigest,
    val completion: HistoricalStageCompletion,
    val work: HistoricalStageWork,
)

sealed interface HistoricalStageAdvance {
    data class Advanced(val receipt: HistoricalStageReceipt) : HistoricalStageAdvance

    data class Stopped(val cause: HistoricalStageStopCause, val receipt: HistoricalStageReceipt) :
        HistoricalStageAdvance
}

internal fun <T> Refinement<T, HistoricalStorageFailure>.stage(): Refinement<T, HistoricalStageFailure> =
    when (this) {
        is Refinement.Refined -> this
        is Refinement.Rejected -> Refinement.Rejected(HistoricalStageFailure.Storage(failure))
    }

internal fun historicalCount(value: Long): HistoricalReadCount =
    when (val count = HistoricalReadCount.parse(value)) {
        is Refinement.Refined -> count.value
        is Refinement.Rejected -> error("Internal row cardinality cannot be negative")
    }

internal inline fun <T> historicalStageEffect(
    block: () -> Refinement<T, HistoricalStageFailure>
): Refinement<T, HistoricalStageFailure> =
    when (val effect = historicalEffect { Refinement.Refined(block()) }) {
        is Refinement.Refined -> effect.value
        is Refinement.Rejected -> Refinement.Rejected(HistoricalStageFailure.Storage(effect.failure))
    }
