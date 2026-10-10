package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** Native status observations are injected; the production coroutine owns polling and the existing deadline. */
internal enum class HostedIndexingState {
    SMART,
    INDEXING,
    DISPOSED,
}

@Serializable
internal enum class HostedSmartModeWaitOutcome {
    STARTED,
    READY,
    TIMED_OUT,
    DISPOSED,
    CANCELLED,
    PLATFORM_RUNTIME_FAILURE,
    PLATFORM_LINKAGE_FAILURE,
}

/** Bounded entry and terminal records, without per-poll output, paths, payloads or exception text. */
@Serializable
internal data class HostedSmartModeWaitObservation(
    val outcome: HostedSmartModeWaitOutcome,
    val elapsedNanos: Long,
    val statusPolls: Long,
    val limitMillis: Long,
)

@Serializable
internal data class HostedCorrelatedSmartModeWait(
    val connectionId: String,
    val wait: HostedSmartModeWaitObservation,
)

// Native platform status providers may throw unchecked exceptions; classify and rethrow at this effect boundary.
@Suppress("TooGenericExceptionCaught")
internal suspend fun waitForHostedSmartMode(
    state: suspend () -> HostedIndexingState,
    observe: (HostedSmartModeWaitObservation) -> Unit,
    clock: () -> Long = System::nanoTime,
): IndexingWait {
    val started = clock()
    var polls = 0L
    fun record(outcome: HostedSmartModeWaitOutcome) =
        observe(
            HostedSmartModeWaitObservation(outcome, (clock() - started).coerceAtLeast(0), polls, INDEXING_WAIT_MILLIS)
        )
    suspend fun awaitStatus(): IndexingWait {
        while (true) {
            polls++
            when (state()) {
                HostedIndexingState.SMART -> return IndexingWait.Ready
                HostedIndexingState.DISPOSED -> return IndexingWait.Disposed
                HostedIndexingState.INDEXING -> delay(INDEXING_POLL_MILLIS)
            }
        }
    }
    val result =
        try {
            withTimeoutOrNull(INDEXING_WAIT_MILLIS) {
                record(HostedSmartModeWaitOutcome.STARTED)
                awaitStatus()
            } ?: IndexingWait.TimedOut
        } catch (failure: RuntimeException) {
            record(
                when (failure) {
                    is CancellationException,
                    is ProcessCanceledException -> HostedSmartModeWaitOutcome.CANCELLED
                    else -> HostedSmartModeWaitOutcome.PLATFORM_RUNTIME_FAILURE
                }
            )
            throw failure
        } catch (failure: LinkageError) {
            record(HostedSmartModeWaitOutcome.PLATFORM_LINKAGE_FAILURE)
            throw failure
        }
    record(
        when (result) {
            IndexingWait.Ready -> HostedSmartModeWaitOutcome.READY
            IndexingWait.TimedOut -> HostedSmartModeWaitOutcome.TIMED_OUT
            IndexingWait.Disposed -> HostedSmartModeWaitOutcome.DISPOSED
        }
    )
    return result
}

private const val INDEXING_WAIT_MILLIS = 15_000L
private const val INDEXING_POLL_MILLIS = 100L
