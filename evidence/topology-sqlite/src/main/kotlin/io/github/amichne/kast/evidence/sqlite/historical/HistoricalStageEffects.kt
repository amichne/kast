package io.github.amichne.kast.evidence.sqlite.historical

import java.io.IOException
import java.sql.SQLException
import java.util.concurrent.CancellationException

enum class HistoricalStageEffectCause {
    IO,
    SQL,
    ACCESS,
}

enum class HistoricalStagePhase {
    WRITE,
    CONTROL,
    COMMIT,
    ROLLBACK,
    CLOSE,
}

sealed interface HistoricalStageFault {
    data class Storage(val phase: HistoricalStagePhase, val cause: HistoricalStageEffectCause) : HistoricalStageFault

    data class Stopped(val cause: HistoricalStageStopCause) : HistoricalStageFault
}

enum class HistoricalStageTransactionUncertainty {
    COMMIT,
    ROLLBACK,
}

sealed interface HistoricalStageProgress {
    data class Settled(val receipt: HistoricalStageReceipt) : HistoricalStageProgress

    /** The receipt describes acknowledged commits, not the unresolved transaction or exact final stage. */
    data class Uncertain(
        val lastConfirmed: HistoricalStageReceipt,
        val transaction: HistoricalStageTransactionUncertainty,
    ) : HistoricalStageProgress
}

sealed interface HistoricalStageCleanupOutcome {
    data object Completed : HistoricalStageCleanupOutcome

    data class Failed(val cause: HistoricalStageEffectCause) : HistoricalStageCleanupOutcome
}

data class HistoricalStageCleanup(
    val rollback: HistoricalStageCleanupOutcome,
    val close: HistoricalStageCleanupOutcome,
)

/** Cancellation still propagates as cancellation and carries progress through transaction cleanup. */
class HistoricalStageCancellationException
internal constructor(
    val progress: HistoricalStageProgress,
    val cleanup: HistoricalStageCleanup,
    cause: CancellationException,
) : CancellationException("Historical stage advance was cancelled") {
    init {
        initCause(cause)
    }
}

internal sealed interface HistoricalStageEffect<out T> {
    data class Completed<T>(val value: T) : HistoricalStageEffect<T>

    data class Failed(val cause: HistoricalStageEffectCause) : HistoricalStageEffect<Nothing>
}

internal inline fun <T> stageEffect(block: () -> T): HistoricalStageEffect<T> =
    try {
        HistoricalStageEffect.Completed(block())
    } catch (_: IOException) {
        HistoricalStageEffect.Failed(HistoricalStageEffectCause.IO)
    } catch (_: SQLException) {
        HistoricalStageEffect.Failed(HistoricalStageEffectCause.SQL)
    } catch (_: SecurityException) {
        HistoricalStageEffect.Failed(HistoricalStageEffectCause.ACCESS)
    }

internal fun HistoricalStageEffect<Unit>.cleanup(): HistoricalStageCleanupOutcome =
    when (this) {
        is HistoricalStageEffect.Completed -> HistoricalStageCleanupOutcome.Completed
        is HistoricalStageEffect.Failed -> HistoricalStageCleanupOutcome.Failed(cause)
    }
