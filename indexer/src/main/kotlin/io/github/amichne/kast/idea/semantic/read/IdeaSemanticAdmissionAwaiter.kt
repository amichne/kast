package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.result.SemanticAdmissionStatus
import kotlinx.coroutines.delay

internal class IdeaSemanticAdmissionAwaiter(
    private val maxWaitMillis: Long,
    private val pollIntervalMillis: Long,
    private val nanoTime: () -> Long = System::nanoTime,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(maxWaitMillis >= 0) { "maxWaitMillis must not be negative" }
        require(pollIntervalMillis > 0) { "pollIntervalMillis must be positive" }
    }

    suspend fun await(
        filePaths: List<NormalizedPath>,
        probe: suspend (NormalizedPath) -> SemanticAdmissionStatus,
    ): Result {
        require(filePaths.isNotEmpty()) { "Semantic admission requires at least one file path" }
        val startedAtNanos = nanoTime()
        var attemptCount = 1
        var fileStatuses = filePaths.map { filePath -> probe(filePath) }

        while (fileStatuses.any(SemanticAdmissionStatus::isPending)) {
            val elapsedMillis = elapsedMillisSince(startedAtNanos)
            if (elapsedMillis >= maxWaitMillis) break
            pause(minOf(pollIntervalMillis, maxWaitMillis - elapsedMillis))
            fileStatuses = fileStatuses.mapIndexed { index, status ->
                if (status.isPending) probe(filePaths[index]) else status
            }
            attemptCount += 1
        }

        return Result(
            fileStatuses = fileStatuses,
            attemptCount = attemptCount,
            elapsedMillis = elapsedMillisSince(startedAtNanos),
        )
    }

    private fun elapsedMillisSince(startedAtNanos: Long): Long =
        ((nanoTime() - startedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND)

    data class Result(
        val fileStatuses: List<SemanticAdmissionStatus>,
        val attemptCount: Int,
        val elapsedMillis: Long,
    )

    companion object {
        private const val DEFAULT_MAX_WAIT_MILLIS = 1_500L
        private const val DEFAULT_POLL_INTERVAL_MILLIS = 25L
        private const val REQUEST_BUDGET_RESERVE_MILLIS = 250L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        fun forRequestBudget(requestTimeoutMillis: Long): IdeaSemanticAdmissionAwaiter {
            val availableWaitMillis = (requestTimeoutMillis - REQUEST_BUDGET_RESERVE_MILLIS).coerceAtLeast(0L)
            return IdeaSemanticAdmissionAwaiter(
                maxWaitMillis = minOf(DEFAULT_MAX_WAIT_MILLIS, availableWaitMillis),
                pollIntervalMillis = DEFAULT_POLL_INTERVAL_MILLIS,
            )
        }
    }
}
