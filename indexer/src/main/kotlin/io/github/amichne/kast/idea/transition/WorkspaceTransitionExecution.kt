package io.github.amichne.kast.idea.transition

import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CancellationException

internal data class TransitionCycle(
    val signals: Set<WorkspaceSignal>,
    val observedEventCount: Long,
    val sourceFreshness: WorkspaceSourceFreshness,
)

/**
 * Effect transition: `(() -> T) -> Result<T>`.
 *
 * Retains ordinary effect failure for the phase owner while cancellation and
 * interruption remain non-recoverable worker-lifecycle transitions.
 */
internal fun <T> runTransitionEffect(operation: () -> T): Result<T> = try {
    Result.success(operation())
} catch (failure: Throwable) {
    rethrowCancellation(failure)
    Result.failure(failure)
}

internal fun rethrowCancellation(failure: Throwable) {
    when (failure) {
        is InterruptedException -> {
            Thread.currentThread().interrupt()
            throw failure
        }

        is CancellationException,
        is ProcessCanceledException,
        -> throw failure
    }
}
