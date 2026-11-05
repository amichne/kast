package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class WorkspacePreparationDeadlineTest {
    @Test
    fun `indefinite allowance has no deadline and reports its actual mode`() {
        val deadline =
            WorkspacePreparationDeadline.start(io.github.amichne.kast.kernel.ElapsedTimeAllowance.Unlimited, 0)
        assertEquals(
            Refinement.Refined(io.github.amichne.kast.kernel.ElapsedTimeAllowance.Unlimited),
            deadline.remaining(9_000_000_000_000L),
        )
        val timing =
            deadline.timing(
                IdeLifecycleStage.IMPORTING,
                WorkspacePreparationDeadlineOwner.PREPARATION_OWNER,
                9_000_000_000_000L,
            )
        assertEquals(WorkspacePreparationWaitMode.INDEFINITE, timing.waitMode)
        org.junit.jupiter.api.Assertions.assertNull(timing.limitMillis)
        org.junit.jupiter.api.Assertions.assertNull(timing.remainingMillis)
    }

    @Test
    fun `child allowance loses every elapsed fraction and never restarts the parent budget`() {
        val limit = (ElapsedTimeLimitMillis.parse(1000) as Refinement.Refined).value
        val deadline = WorkspacePreparationDeadline.start(limit, 5_000_000_000)
        assertEquals(999, (deadline.remaining(5_000_000_001) as Refinement.Refined).value.protocolMillis())
        assertEquals(300, (deadline.remaining(5_700_000_000) as Refinement.Refined).value.protocolMillis())
        assertInstanceOf(Refinement.Rejected::class.java, deadline.remaining(6_000_000_000))
        assertEquals(
            WorkspacePreparationTiming(
                IdeLifecycleStage.IMPORTING,
                1000,
                1000,
                0,
                WorkspacePreparationDeadlineOwner.NATIVE_PREPARATION,
            ),
            deadline.timing(
                IdeLifecycleStage.IMPORTING,
                WorkspacePreparationDeadlineOwner.NATIVE_PREPARATION,
                6_000_000_000,
            ),
        )
    }
}
