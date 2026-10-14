package io.github.amichne.kast.distribution.cli

import java.time.Duration

internal enum class ResetRetirementPause {
    CONTINUE,
    INTERRUPTED,
}

internal class ResetRetirementTiming(
    val clock: () -> Long = System::nanoTime,
    val pause: (Duration) -> ResetRetirementPause = ::pauseResetRetirement,
)

private fun pauseResetRetirement(duration: Duration): ResetRetirementPause =
    try {
        Thread.sleep(duration)
        ResetRetirementPause.CONTINUE
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        ResetRetirementPause.INTERRUPTED
    }
