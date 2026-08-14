package io.github.amichne.kast.protocol.continuation

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class RegisteredLongOperationStoreTest {
    @Test
    fun `registration outlives request scope and preserves exact binding`() {
        val scheduler = ManualScheduler()
        val store = store(scheduler = scheduler)
        val expectedBinding = binding()
        val id = registerAndReturn(store, expectedBinding)

        val mismatches = listOf(
            binding(root = Path.of("/other")) to LongOperationAccessFailure.WRONG_WORKSPACE_ROOT,
            binding(requester = "requester-2") to LongOperationAccessFailure.REQUESTER_CHANGED,
            binding(runtimeEpoch = "runtime-8") to LongOperationAccessFailure.RUNTIME_EPOCH_CHANGED,
            binding(capability = "description") to LongOperationAccessFailure.CAPABILITY_CHANGED,
            binding(input = "symbol:other") to LongOperationAccessFailure.INPUT_CHANGED,
        )
        mismatches.forEach { (presented, failure) ->
            assertEquals(failure, store.poll(id, presented).rejected())
        }
        assertEquals(
            LongOperationState.Running,
            store.poll(id, expectedBinding).observed(),
        )

        val completed = LongOperationCompletion.Succeeded(
            DetachedContinuationRecord.fromCanonical("definition"),
        )
        assertEquals(
            LongOperationCompletionResult.Completed,
            store.complete(id, expectedBinding, completed),
        )
        assertEquals(
            LongOperationState.Terminal(
                LongOperationTerminalResult.Succeeded(completed.output),
            ),
            store.poll(id, expectedBinding).observed(),
        )
    }

    @Test
    fun `deadline fires without polling and polling never renews it`() {
        val clock = MutableClock()
        val scheduler = ManualScheduler()
        val store = store(clock = clock, scheduler = scheduler)
        val expectedBinding = binding()
        val id = store.start(expectedBinding).started()

        assertEquals(LongOperationState.Running, store.poll(id, expectedBinding).observed())
        assertEquals(listOf(10_000_000L), scheduler.delays())
        clock.nowNanos = 10_000_000L
        scheduler.fire(0)

        val expected = LongOperationState.Terminal(
            LongOperationTerminalResult.Failed(
                LongOperationTerminalFailure.DEADLINE_EXCEEDED,
            ),
        )
        assertEquals(expected, store.poll(id, expectedBinding).observed())
        assertEquals(expected, store.poll(id, expectedBinding).observed())
        assertEquals(listOf(10_000_000L, 5_000_000L), scheduler.delays())
    }

    @Test
    fun `terminal retention is bounded and stale cleanup cannot remove a reused id`() {
        val clock = MutableClock()
        val scheduler = ManualScheduler()
        val issuer = LongOperationIdIssuer { operationId(1) }
        val store = store(
            capacity = 1,
            clock = clock,
            scheduler = scheduler,
            idIssuer = issuer,
        )
        val expectedBinding = binding()
        val first = store.start(expectedBinding).started()
        assertEquals(
            LongOperationCompletionResult.Completed,
            store.complete(first, expectedBinding, LongOperationCompletion.Failed),
        )
        assertEquals(
            LongOperationTerminalFailure.EXECUTION_FAILED,
            store.poll(first, expectedBinding).observed().terminalFailure(),
        )
        assertEquals(
            LongOperationStartFailure.CAPACITY_REACHED,
            store.start(expectedBinding).rejected(),
        )

        clock.nowNanos = 5_000_000L
        assertEquals(
            LongOperationAccessFailure.EXPIRED,
            store.poll(first, expectedBinding).rejected(),
        )
        assertEquals(listOf(1, 1), scheduler.cancelCounts())
        scheduler.fire(1)
        assertEquals(
            LongOperationAccessFailure.UNKNOWN_OPERATION,
            store.poll(first, expectedBinding).rejected(),
        )

        val replacement = store.start(expectedBinding).started()
        assertEquals(first, replacement)
        scheduler.fire(1)
        assertEquals(listOf(1, 1, 0), scheduler.cancelCounts())
        assertEquals(
            LongOperationState.Running,
            store.poll(replacement, expectedBinding).observed(),
        )
    }

    @Test
    fun `cancellation is closed and terminal failures remain replayable`() {
        val scheduler = ManualScheduler()
        val store = store(scheduler = scheduler)
        val expectedBinding = binding()
        val unsupported = store.start(expectedBinding).started()

        assertEquals(
            LongOperationAccessFailure.CANCELLATION_UNSUPPORTED,
            store.cancel(unsupported, expectedBinding).rejected(),
        )
        assertEquals(
            LongOperationState.Running,
            store.poll(unsupported, expectedBinding).observed(),
        )

        val supported = store.start(
            expectedBinding,
            LongOperationCancellationPolicy.SUPPORTED,
        ).started()
        assertEquals(
            LongOperationCancellationResult.Cancelled,
            store.cancel(supported, expectedBinding),
        )
        val first = store.poll(supported, expectedBinding).observed()
        val second = store.poll(supported, expectedBinding).observed()
        assertEquals(first, second)
        assertEquals(LongOperationTerminalFailure.CANCELLED, first.terminalFailure())
    }

    @Test
    fun `capacity collisions and scheduler rejection fail before publication`() {
        val scheduler = ManualScheduler()
        val expectedBinding = binding()
        val full = store(capacity = 1, scheduler = scheduler)
        full.start(expectedBinding).started()
        assertEquals(
            LongOperationStartFailure.CAPACITY_REACHED,
            full.start(expectedBinding).rejected(),
        )

        val collision = store(
            capacity = 2,
            scheduler = ManualScheduler(),
            idIssuer = LongOperationIdIssuer { operationId(1) },
        )
        collision.start(expectedBinding).started()
        assertEquals(
            LongOperationStartFailure.ID_COLLISION,
            collision.start(expectedBinding).rejected(),
        )

        val rejecting = store(scheduler = ManualScheduler(rejectNext = true))
        assertEquals(
            LongOperationStartFailure.DEADLINE_SCHEDULER_REJECTED,
            rejecting.start(expectedBinding).rejected(),
        )
        ManualScheduler().also { recoveredScheduler ->
            store(scheduler = recoveredScheduler).start(expectedBinding).started()
            assertEquals(1, recoveredScheduler.delays().size)
        }

        val retentionScheduler = ManualScheduler()
        val retentionStore = store(scheduler = retentionScheduler)
        val operationId = retentionStore.start(expectedBinding).started()
        retentionScheduler.rejectOnce()
        assertEquals(
            LongOperationAccessFailure.RETENTION_SCHEDULER_REJECTED,
            (
                retentionStore.complete(
                    operationId,
                    expectedBinding,
                    LongOperationCompletion.Failed,
                ) as LongOperationCompletionResult.Rejected
            ).failure,
        )
        assertEquals(
            LongOperationAccessFailure.UNKNOWN_OPERATION,
            retentionStore.poll(operationId, expectedBinding).rejected(),
        )
        assertEquals(listOf(1), retentionScheduler.cancelCounts())
    }

    @Test
    fun `boundary refinement issuer failure and close remain finite`() {
        assertEquals(
            LongOperationIdFailure.MALFORMED,
            LongOperationId.parse("bad").rejected(),
        )
        assertEquals(
            LongOperationIdFailure.NON_CANONICAL,
            LongOperationId.parse("10000000-0000-0000-0000-00000000000A").rejected(),
        )
        assertEquals(
            LongOperationPositiveLimitFailure.NOT_POSITIVE,
            LongOperationCapacity.parse(0).rejected(),
        )
        assertEquals(
            LongOperationDurationFailure.TOO_LARGE,
            LongOperationDeadlineMillis.parse(Long.MAX_VALUE).rejected(),
        )

        val expectedBinding = binding()
        assertEquals(
            LongOperationStartFailure.ID_ISSUER_FAILURE,
            store(
                scheduler = ManualScheduler(),
                idIssuer = LongOperationIdIssuer { error("issuer failed") },
            ).start(expectedBinding).rejected(),
        )

        val scheduler = ManualScheduler()
        val closed = store(scheduler = scheduler)
        closed.start(expectedBinding).started()
        closed.close()
        closed.close()
        assertEquals(listOf(1), scheduler.cancelCounts())
        assertEquals(
            LongOperationStartFailure.STORE_CLOSED,
            closed.start(expectedBinding).rejected(),
        )
    }

    @Test
    fun `registered surfaces retain no live operation or IntelliJ shaped object`() {
        val forbidden = Regex(
            "com\\.intellij|Psi|VirtualFile|GlobalSearchScope|java\\.sql|Future|Executor|Function",
        )
        val surface = listOf(
            RegisteredLongOperationStore::class.java,
            LongOperationBinding::class.java,
            LongOperationState::class.java,
            LongOperationTerminalResult::class.java,
            RegisteredLongOperation::class.java,
            LongOperationPhase::class.java,
        ).flatMap { type ->
            type.declaredFields.map { it.genericType.typeName } +
            type.declaredMethods.flatMap { method ->
                listOf(method.genericReturnType.typeName) +
                method.genericParameterTypes.map { it.typeName }
            }
        }
        assertEquals(emptyList<String>(), surface.filter(forbidden::containsMatchIn))
    }

    private fun registerAndReturn(
        store: RegisteredLongOperationStore,
        expectedBinding: LongOperationBinding,
    ): LongOperationId = store.start(expectedBinding).started()

    private fun store(
        capacity: Int = 8,
        clock: ContinuationClock = ContinuationClock { 0L },
        scheduler: ManualScheduler,
        idIssuer: LongOperationIdIssuer = IncrementingIdIssuer(),
    ): RegisteredLongOperationStore = RegisteredLongOperationStore(
        policy = LongOperationPolicy(
            capacity = LongOperationCapacity.parse(capacity).refined(),
            deadline = LongOperationDeadlineMillis.parse(10L).refined(),
            terminalRetention = LongOperationRetentionMillis.parse(5L).refined(),
        ),
        idIssuer = idIssuer,
        clock = clock,
        scheduler = scheduler,
    )

    private fun binding(
        root: Path = Path.of("/workspace"),
        requester: String = "requester-1",
        runtimeEpoch: String = "runtime-7",
        capability: String = "definition",
        input: String = "symbol:com.example.Type",
    ): LongOperationBinding = LongOperationBinding(
        workspaceRoot = CanonicalWorkspaceRoot.fromCanonicalPath(root).refined(),
        requester = LongOperationRequester.fromCanonical(requester),
        runtimeEpoch = LongOperationRuntimeEpoch.fromCanonical(runtimeEpoch),
        declaredCapability = LongOperationCapability.fromCanonical(capability),
        inputIdentity = LongOperationInputIdentity.fromCanonical(input),
    )

    private fun operationId(value: Int): LongOperationId = LongOperationId.parse(
        "10000000-0000-0000-0000-${value.toString().padStart(12, '0')}",
    ).refined()

    private inner class IncrementingIdIssuer : LongOperationIdIssuer {
        private var next = 1

        override fun issue(): LongOperationId = operationId(next++)
    }

    private class MutableClock : ContinuationClock {
        var nowNanos: Long = 0L

        override fun nowNanos(): Long = nowNanos
    }

    private class ManualScheduler(
        private var rejectNext: Boolean = false,
    ) : LongOperationScheduler {
        private val tasks = mutableListOf<ManualTask>()

        override fun arm(
            delay: LongOperationDelay,
            signal: LongOperationScheduledSignal,
        ): LongOperationScheduleResult {
            if (rejectNext) {
                rejectNext = false
                return LongOperationScheduleResult.Rejected
            }
            val task = ManualTask(delay.nanoseconds, signal)
            tasks += task
            return LongOperationScheduleResult.Armed(task)
        }

        fun delays(): List<Long> = tasks.map(ManualTask::delayNanos)

        fun cancelCounts(): List<Int> = tasks.map(ManualTask::cancelCount)

        fun rejectOnce() {
            rejectNext = true
        }

        fun fire(index: Int) {
            tasks[index].signal.fire()
        }

        private data class ManualTask(
            val delayNanos: Long,
            val signal: LongOperationScheduledSignal,
        ) : LongOperationScheduledTask {
            var cancelCount = 0

            override fun cancel() {
                cancelCount += 1
            }
        }
    }

    private fun LongOperationStartResult.started(): LongOperationId =
        (this as LongOperationStartResult.Started).operationId

    private fun LongOperationStartResult.rejected(): LongOperationStartFailure =
        (this as LongOperationStartResult.Rejected).failure

    private fun LongOperationPollResult.observed(): LongOperationState =
        (this as LongOperationPollResult.Observed).state

    private fun LongOperationPollResult.rejected(): LongOperationAccessFailure =
        (this as LongOperationPollResult.Rejected).failure

    private fun LongOperationCancellationResult.rejected(): LongOperationAccessFailure =
        (this as LongOperationCancellationResult.Rejected).failure

    private fun LongOperationState.terminalFailure(): LongOperationTerminalFailure =
        ((this as LongOperationState.Terminal).result as LongOperationTerminalResult.Failed).failure

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.rejected(): Failure = when (this) {
        is Refinement.Refined -> error(value.toString())
        is Refinement.Rejected -> failure
    }
}
