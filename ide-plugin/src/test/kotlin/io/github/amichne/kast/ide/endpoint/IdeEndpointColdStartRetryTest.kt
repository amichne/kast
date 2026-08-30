package io.github.amichne.kast.ide.endpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class IdeEndpointColdStartRetryTest {
    @Test
    fun `deferred startup advances through a finite capped retry cadence`() =
        withDirectory { directory ->
            val coordinator = coordinator()
            val initialAttempt = initialAttempt(coordinator)

            val prompt = deferred(coordinator, initialAttempt)
            assertEquals(IdeEndpointDeferredRetry.PROMPT, prompt.retry.cadence)
            assertEquals(250.milliseconds, prompt.retry.cadence.duration)
            val settlingAttempt = scheduledAttempt(coordinator, prompt)
            assertEquals(
                IdeEndpointSignalPlan.Coalesced,
                coordinator.planRetry(prompt.retry),
            )

            val settling = deferred(coordinator, settlingAttempt)
            assertEquals(IdeEndpointDeferredRetry.SETTLING, settling.retry.cadence)
            assertEquals(1.seconds, settling.retry.cadence.duration)
            val quiescentAttempt = scheduledAttempt(coordinator, settling)

            val quiescent = deferred(coordinator, quiescentAttempt)
            assertEquals(IdeEndpointDeferredRetry.QUIESCENT, quiescent.retry.cadence)
            assertEquals(3.seconds, quiescent.retry.cadence.duration)
            val cappedAttempt = scheduledAttempt(coordinator, quiescent)

            val capped = deferred(coordinator, cappedAttempt)
            assertEquals(IdeEndpointDeferredRetry.QUIESCENT, capped.retry.cadence)
            val preparedAttempt = scheduledAttempt(coordinator, capped)

            assertTrue(
                coordinator.planCompletion(
                    preparedAttempt,
                    IdeEndpointStartup.Prepared(prepareEndpoint(directory).prepared()),
                ) is IdeEndpointCompletionPlan.Activate,
            )
        }

    @Test
    fun `readiness signal makes an older scheduled retry stale`() {
        val coordinator = coordinator()
        val prompt = deferred(coordinator, initialAttempt(coordinator))

        assertTrue(coordinator.planSignal() is IdeEndpointSignalPlan.Launch)
        assertEquals(IdeEndpointSignalPlan.Coalesced, coordinator.planRetry(prompt.retry))
    }

    @Test
    fun `signal coalesced during an attempt issues an immediate retry attempt`() {
        val coordinator = coordinator()
        val attempt = initialAttempt(coordinator)
        assertEquals(IdeEndpointSignalPlan.Coalesced, coordinator.planSignal())

        val retry = coordinator.planCompletion(
            attempt,
            IdeEndpointStartup.Deferred(IdeEndpointDeferredReadiness.DUMB_MODE),
        )

        assertTrue(retry is IdeEndpointCompletionPlan.Retry)
        assertEquals(IdeEndpointSignalPlan.Coalesced, coordinator.planSignal())
    }

    private fun coordinator() = IdeEndpointCoordinator(IdeEndpointPublisher {
        error("endpoint publication is outside this proof")
    })

    private fun initialAttempt(coordinator: IdeEndpointCoordinator): IdeEndpointAttempt {
        assertEquals(IdeEndpointServiceStart.Started, coordinator.begin())
        val launch = coordinator.listenersInstalled()
        assertTrue(launch is IdeEndpointSignalPlan.Launch)
        return (launch as IdeEndpointSignalPlan.Launch).attempt
    }

    private fun deferred(
        coordinator: IdeEndpointCoordinator,
        attempt: IdeEndpointAttempt,
    ): IdeEndpointCompletionPlan.RetryAfter {
        val completion = coordinator.planCompletion(
            attempt,
            IdeEndpointStartup.Deferred(IdeEndpointDeferredReadiness.GRADLE_MODEL_INCOMPLETE),
        )
        assertTrue(completion is IdeEndpointCompletionPlan.RetryAfter)
        return completion as IdeEndpointCompletionPlan.RetryAfter
    }

    private fun scheduledAttempt(
        coordinator: IdeEndpointCoordinator,
        retry: IdeEndpointCompletionPlan.RetryAfter,
    ): IdeEndpointAttempt {
        val launch = coordinator.planRetry(retry.retry)
        assertTrue(launch is IdeEndpointSignalPlan.Launch)
        return (launch as IdeEndpointSignalPlan.Launch).attempt
    }
}
