package io.github.amichne.kast.runtime.hosted.lifecycle

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Existing lifecycle wait bounds; peer reads do not use this lifecycle effect. */
internal suspend fun awaitLifecycleCondition(condition: () -> Boolean): Boolean =
    withTimeoutOrNull(OPERATION_WAIT_MILLIS) {
        while (!condition()) delay(POLL_MILLIS)
        true
    } ?: false

private const val OPERATION_WAIT_MILLIS = 120_000L
private const val POLL_MILLIS = 100L
