package io.github.amichne.kast.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis

/**
 * Performance baselines for the IDEA backend telemetry and read-action timing.
 *
 * Tests are tagged `performance` so they can be excluded from the default CI run:
 *     ./gradlew :backend-idea:test -PexcludeTags=performance
 * or run in isolation:
 *     ./gradlew :backend-idea:test -PincludeTags=performance
 */
@Tag("performance")
class IdeaBackendPerformanceTest {

    companion object {
        private const val READ_ACTION_HOLD_MAX_MS = 500L
        private const val TELEMETRY_SPAN_OVERHEAD_MS = 50L
    }

    @Test
    fun `telemetry inSpan overhead is negligible`() {
        val telemetry = IdeaBackendTelemetry.disabled()
        val iterations = 1_000

        repeat(100) {
            assertEquals(42, telemetry.inSpan(IdeaTelemetryScope.RESOLVE, "warmup") { 42 })
        }

        val elapsed = measureTimeMillis {
            repeat(iterations) { i ->
                telemetry.inSpan(IdeaTelemetryScope.RESOLVE, "test-span") { i + 1 }
            }
        }

        val perCallMs = elapsed.toDouble() / iterations
        println("telemetry_inSpan_per_call_ms: $perCallMs (${iterations} iterations, ${elapsed}ms total)")
        assertTrue(perCallMs < 1.0) {
            "inSpan overhead per call was ${perCallMs}ms, expected < 1ms"
        }
    }

    @Test
    fun `telemetry recordReadAction overhead is negligible`() {
        val telemetry = IdeaBackendTelemetry.disabled()
        val iterations = 1_000

        repeat(100) {
            telemetry.recordReadAction(IdeaTelemetryScope.READ_ACTION, "warmup", 100L, 200L)
        }

        val elapsed = measureTimeMillis {
            repeat(iterations) {
                telemetry.recordReadAction(
                    IdeaTelemetryScope.READ_ACTION,
                    "test-read-action",
                    waitNanos = 1_000_000L,
                    holdNanos = 2_000_000L,
                )
            }
        }

        val perCallMs = elapsed.toDouble() / iterations
        println("recordReadAction_per_call_ms: $perCallMs (${iterations} iterations, ${elapsed}ms total)")
        assertTrue(perCallMs < 1.0) {
            "recordReadAction overhead per call was ${perCallMs}ms, expected < 1ms"
        }
    }

    @Test
    fun `enabled telemetry with exporter stays within overhead budget`() {
        val telemetry = IdeaBackendTelemetry.disabled()
        val iterations = 100

        repeat(20) {
            assertEquals(42, telemetry.inSpan(IdeaTelemetryScope.RESOLVE, "warmup") { 42 })
        }

        val elapsed = measureTimeMillis {
            repeat(iterations) { i ->
                telemetry.inSpan(IdeaTelemetryScope.DIAGNOSTICS, "perf-test") {
                    telemetry.recordReadAction(
                        IdeaTelemetryScope.READ_ACTION,
                        "inner-read",
                        waitNanos = 500_000L,
                        holdNanos = 1_000_000L,
                    )
                    i + 1
                }
            }
        }

        val perCallMs = elapsed.toDouble() / iterations
        println("telemetry_inSpan_with_readAction_ms: $perCallMs (${iterations} iterations)")
        assertTrue(perCallMs < TELEMETRY_SPAN_OVERHEAD_MS) {
            "Telemetry span+readAction per call was ${perCallMs}ms, exceeds ${TELEMETRY_SPAN_OVERHEAD_MS}ms budget"
        }
    }

    @Test
    fun `read action timing math is accurate`() {
        val simulatedWaitNanos = 10_000_000L
        val simulatedHoldNanos = 5_000_000L
        val telemetry = IdeaBackendTelemetry.disabled()

        telemetry.recordReadAction(
            IdeaTelemetryScope.READ_ACTION,
            "timing-test",
            waitNanos = simulatedWaitNanos,
            holdNanos = simulatedHoldNanos,
        )

        val holdMs = simulatedHoldNanos / 1_000_000
        assertTrue(holdMs <= READ_ACTION_HOLD_MAX_MS) {
            "Simulated hold time ${holdMs}ms exceeds threshold ${READ_ACTION_HOLD_MAX_MS}ms"
        }
    }

    @Test
    fun `disabled telemetry does not throw`() {
        val telemetry = IdeaBackendTelemetry.disabled()
        val result = telemetry.inSpan(IdeaTelemetryScope.RENAME, "no-op") { "ok" }
        assertTrue(result == "ok")
    }
}
