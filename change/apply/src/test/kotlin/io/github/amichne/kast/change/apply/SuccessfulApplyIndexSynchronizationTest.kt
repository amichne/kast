package io.github.amichne.kast.change.apply

import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executor

class SuccessfulApplyIndexSynchronizationTest {
    private val fixture = ApplyTestFixture()

    @Test
    fun `only applied unverified schedules synchronization`() {
        val schedules = AtomicInteger()
        val successful = SuccessfulApplyIndexSynchronization(
            successfulService(),
            AppliedIndexSynchronizationScheduler {
                schedules.incrementAndGet()
                AppliedIndexSynchronizationSchedule.Scheduled
            },
        )

        assertInstanceOf(AppliedUnverified::class.java, successful.apply(fixture.request()))
        assertEquals(1, schedules.get())

        val rejected = SuccessfulApplyIndexSynchronization(
            AddDeclarationApplyOperations {
                AddDeclarationApplyResult.Rejected(
                    AddDeclarationApplyFailure.Admission(
                        MutationAdmissionFailure.STALE_GENERATION,
                    ),
                )
            },
            AppliedIndexSynchronizationScheduler {
                schedules.incrementAndGet()
                AppliedIndexSynchronizationSchedule.Scheduled
            },
        )
        assertInstanceOf(
            AddDeclarationApplyResult.Rejected::class.java,
            rejected.apply(fixture.request()),
        )
        assertEquals(1, schedules.get())
    }

    @Test
    fun `scheduler rejection cannot weaken successful apply result`() {
        val operations = SuccessfulApplyIndexSynchronization(
            successfulService(),
            AppliedIndexSynchronizationScheduler {
                AppliedIndexSynchronizationSchedule.Rejected(
                    AppliedIndexSynchronizationScheduleFailure.EXECUTOR_UNAVAILABLE,
                )
            },
        )

        assertInstanceOf(AppliedUnverified::class.java, operations.apply(fixture.request()))
    }

    @Test
    fun `asynchronous scheduler coalesces while synchronization is queued`() {
        val queued = ArrayDeque<Runnable>()
        val executions = AtomicInteger()
        val scheduler = CoalescingAppliedIndexSynchronizationScheduler(
            Executor(queued::addLast),
            AppliedIndexSynchronizationTask { executions.incrementAndGet() },
        )

        assertEquals(AppliedIndexSynchronizationSchedule.Scheduled, scheduler.schedule())
        assertEquals(AppliedIndexSynchronizationSchedule.Coalesced, scheduler.schedule())
        assertEquals(0, executions.get())

        queued.removeFirst().run()

        assertEquals(1, executions.get())
        assertEquals(AppliedIndexSynchronizationSchedule.Scheduled, scheduler.schedule())
    }

    private fun successfulService(): AddDeclarationApplyOperations = AddDeclarationApplyOperations {
        AppliedUnverified.restore(
            fixture.plan,
            fixture.workspace.readLease,
            fixture.workspace.sourceState,
            WorkspaceSourceContentHash.parse("b".repeat(64)).refined(),
            MutationPlanBinding.parse(fixture.plan.planId.value).refined(),
        ).refined()
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        (this as Refinement.Refined).value
}
