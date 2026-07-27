package io.github.amichne.kast.headless

import java.time.Duration

data class HeadlessGradleImportTransition(
    val observation: HeadlessGradleImportObservation,
    val firstObservedAt: Duration,
    val lastObservedAt: Duration,
    val occurrenceCount: Long,
) {
    init {
        require(!firstObservedAt.isNegative) { "firstObservedAt must not be negative" }
        require(lastObservedAt >= firstObservedAt) { "lastObservedAt must not precede firstObservedAt" }
        require(occurrenceCount > 0) { "occurrenceCount must be positive" }
    }

    fun repeatAt(elapsed: Duration): HeadlessGradleImportTransition =
        copy(
            lastObservedAt = elapsed,
            occurrenceCount = occurrenceCount + 1,
        )
}
