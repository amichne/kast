@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class WorkspacePreparationsTest {
    private val root = CanonicalRoot(Path.of("/workspace"))
    private val requestId = checkNotNull(WorkspacePreparationId.admit("00000000-0000-0000-0000-000000000001"))
    private val target =
        IdeProjectTarget("00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000003", "/workspace")

    @Test
    fun `concurrent demand opens exact root once and retains readiness identity`() = runTest {
        val requests = mutableListOf<WorkspaceLifecycleRequest>()
        val events = mutableListOf<WorkspacePreparationActivity>()
        val preparations =
            WorkspacePreparations(
                this,
                { request ->
                    requests += request
                    assertEquals(1, requests.size, "unexpected lifecycle exchange")
                    IdeLifecycleResult.Opened(target)
                },
                observer = WorkspacePreparationObserver { events += it },
                newId = { requestId },
            )
        val first = (preparations.prepare(root) as Refinement.Refined).value
        val joined = (preparations.prepare(CanonicalRoot(Path.of("/workspace"))) as Refinement.Refined).value
        assertSame(first, joined)
        assertEquals(setOf(UpgradeBlocker.PREPARATION_ACTIVE), preparations.upgradeBlockers())
        runCurrent()
        assertEquals(emptySet<UpgradeBlocker>(), preparations.upgradeBlockers())
        assertEquals(listOf(WorkspaceLifecycleRequest.Open("/workspace", requestId.value.toString())), requests)
        val completed = first.state.value as WorkspacePreparationOutcome.Complete
        assertEquals(root, completed.workspace.root)
        assertEquals(target, completed.workspace.target)
        assertEquals(
            listOf(
                WorkspacePreparationActivityOutcome.Pending(IdeLifecycleStage.OPENING),
                WorkspacePreparationActivityOutcome.Completed,
            ),
            events.map { it.outcome },
        )
        preparations.close()
    }

    @Test
    fun `passive observation and cancelled waiter never restart pending preparation`() = runTest {
        val requests = mutableListOf<WorkspaceLifecycleRequest>()
        val preparations =
            WorkspacePreparations(
                this,
                { request ->
                    requests += request
                    when (requests.size) {
                        1 ->
                            IdeLifecycleResult.Pending(
                                requestId.value.toString(),
                                IdeLifecycleStage.IMPORTING,
                                target.host,
                            )
                        2 -> IdeLifecycleResult.Opened(target)
                        else -> error("unexpected lifecycle exchange")
                    }
                },
                newId = { requestId },
            )
        val entry = (preparations.prepare(root) as Refinement.Refined).value
        runCurrent()
        val waiter = async { entry.state.first { it !is WorkspacePreparationOutcome.Pending } }
        runCurrent()
        waiter.cancel()
        repeat(3) {
            assertSame(entry, (preparations.observe(requestId) as Refinement.Refined).value)
            assertSame(entry, (preparations.prepare(root) as Refinement.Refined).value)
        }
        assertEquals(1, requests.size)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(WorkspaceLifecycleRequest.Status(target.host, requestId.value.toString()), requests[1])
        assertEquals(target, (entry.state.value as WorkspacePreparationOutcome.Complete).workspace.target)
        preparations.close()
    }

    @Test
    fun `deadline remains terminal and never replays an uncertain open`() = runTest {
        var calls = 0
        val preparations =
            WorkspacePreparations(
                this,
                {
                    calls++
                    awaitCancellation()
                },
                budget = 1.seconds,
                newId = { requestId },
            )
        val entry = (preparations.prepare(root) as Refinement.Refined).value
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.DEADLINE_EXCEEDED),
            entry.state.value,
        )
        assertSame(entry, (preparations.prepare(root) as Refinement.Refined).value)
        assertEquals(1, calls)
        preparations.close()
    }

    @Test
    fun `capacity rejects excess roots and close settles queued admission`() = runTest {
        val preparations =
            WorkspacePreparations(
                this,
                { error("unexpected exchange after close") },
                capacity = 1,
                newId = { requestId },
            )
        val entry = (preparations.prepare(root) as Refinement.Refined).value
        assertEquals(
            Refinement.Rejected(WorkspacePreparationFailure.CAPACITY_EXCEEDED),
            preparations.prepare(CanonicalRoot(Path.of("/other"))),
        )
        preparations.close()
        runCurrent()
        assertEquals(WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.CLOSED), entry.state.value)
        assertEquals(Refinement.Rejected(WorkspacePreparationFailure.CLOSED), preparations.prepare(root))
        assertSame(entry, (preparations.observe(requestId) as Refinement.Refined).value)
    }

    @Test
    fun `foreign root incarnation and unexpected outcome cannot become readiness`() = runTest {
        val replies =
            listOf(
                IdeLifecycleResult.Opened(target.copy(root = "/other")),
                IdeLifecycleResult.Opened(target.copy(host = "not-a-host")),
                IdeLifecycleResult.Opened(target.copy(project = "not-a-project")),
                IdeLifecycleResult.Presented(target),
                IdeLifecycleResult.Pending("foreign-request", IdeLifecycleStage.OPENING, target.host),
                IdeLifecycleResult.Pending(requestId.value.toString(), IdeLifecycleStage.CLOSING, target.host),
            )
        for (reply in replies) {
            var calls = 0
            val preparations =
                WorkspacePreparations(
                    this,
                    {
                        calls++
                        reply
                    },
                    newId = { requestId },
                )
            val entry = (preparations.prepare(root) as Refinement.Refined).value
            runCurrent()
            assertEquals(
                WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.RESPONSE_REJECTED),
                entry.state.value,
            )
            assertEquals(1, calls)
            preparations.close()
        }
    }

    @Test
    fun `lifecycle blocked reasons remain finite and terminal`() = runTest {
        for (reason in IdeLifecycleFailure.entries) {
            val events = mutableListOf<WorkspacePreparationActivity>()
            val preparations =
                WorkspacePreparations(
                    this,
                    { IdeLifecycleResult.Blocked(reason) },
                    observer = WorkspacePreparationObserver { events += it },
                    newId = { requestId },
                )
            val entry = (preparations.prepare(root) as Refinement.Refined).value
            runCurrent()
            assertEquals(WorkspacePreparationOutcome.Blocked(reason), entry.state.value)
            assertEquals(WorkspacePreparationActivityOutcome.Blocked(reason), events.last().outcome)
            assertSame(entry, (preparations.prepare(root) as Refinement.Refined).value)
            preparations.close()
        }
    }

    @Test
    fun `pending progress retains host incarnation and never regresses a proven phase`() {
        val previous = NativePreparationProgress(java.util.UUID.fromString(target.host), IdeLifecycleStage.IMPORTING)
        val rejected =
            listOf(
                IdeLifecycleResult.Pending(requestId.value.toString(), IdeLifecycleStage.OPENING, target.host),
                IdeLifecycleResult.Pending(
                    requestId.value.toString(),
                    IdeLifecycleStage.ADMISSION,
                    "00000000-0000-0000-0000-000000000004",
                ),
            )
        rejected.forEach {
            assertEquals(
                Refinement.Rejected(WorkspacePreparationFailure.RESPONSE_REJECTED),
                admitPreparationProgress(it, requestId, previous),
            )
        }
        val next = IdeLifecycleResult.Pending(requestId.value.toString(), IdeLifecycleStage.ADMISSION, target.host)
        assertEquals(
            Refinement.Refined(NativePreparationProgress(previous.host, IdeLifecycleStage.ADMISSION)),
            admitPreparationProgress(next, requestId, previous),
        )
    }
}
