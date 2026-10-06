@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

internal class WorkspaceAvailabilityRecoveryFailureTest {
    private val root = CanonicalRoot(Path.of("/workspace"))
    private val firstId = id("00000000-0000-0000-0000-000000000001")
    private val retryId = id("00000000-0000-0000-0000-000000000002")
    private val target =
        IdeProjectTarget("00000000-0000-0000-0000-000000000003", "00000000-0000-0000-0000-000000000004", "/workspace")

    @Test
    fun `nonavailability failures remain terminal without inspection or semantic execution`() = runTest {
        val excluded =
            setOf(
                IdeLifecycleFailure.HOST_UNAVAILABLE,
                IdeLifecycleFailure.PLUGIN_UNAVAILABLE,
                IdeLifecycleFailure.COMPATIBILITY_REJECTED,
            )
        for (reason in IdeLifecycleFailure.entries.filterNot { it in excluded }) {
            val script = Script(listOf(open(firstId) to IdeLifecycleResult.Blocked(reason)))
            val preparations = WorkspacePreparations(this, script::exchange, newId = { firstId })
            val entry = (preparations.prepare(root) as Refinement.Refined).value
            runCurrent()
            val demand = rejectedDemand(preparations)
            repeat(2) {
                val result = async { demand.query(root, ExistingIdeOperation.Status) }
                runCurrent()
                assertEquals(WorkspaceDemandCause.Lifecycle(reason), operationFailure(result.await()).cause)
            }
            assertSame(entry, (preparations.prepare(root) as Refinement.Refined).value)
            script.assertDrained()
            preparations.close()
        }
    }

    @Test
    fun `fresh inspection refusal retains its exact reason and never reopens the project`() = runTest {
        for (reason in IdeLifecycleFailure.entries) {
            val script =
                Script(
                    listOf(
                        open(firstId) to IdeLifecycleResult.Blocked(IdeLifecycleFailure.PLUGIN_UNAVAILABLE),
                        WorkspaceLifecycleRequest.Inspect to IdeLifecycleResult.Blocked(reason),
                    )
                )
            val ids = ArrayDeque(listOf(firstId, retryId))
            val events = mutableListOf<WorkspacePreparationActivity>()
            val preparations =
                WorkspacePreparations(
                    this,
                    script::exchange,
                    observer = WorkspacePreparationObserver { events += it },
                    newId = { ids.removeFirst() },
                )
            val original = (preparations.prepare(root) as Refinement.Refined).value
            runCurrent()
            val result = async { rejectedDemand(preparations).query(root, ExistingIdeOperation.Status) }
            runCurrent()
            val failure = operationFailure(result.await())
            assertEquals(retryId, failure.id)
            assertEquals(WorkspaceDemandCause.Lifecycle(reason), failure.cause)
            assertEquals(
                WorkspacePreparationOutcome.Blocked(IdeLifecycleFailure.PLUGIN_UNAVAILABLE),
                original.state.value,
            )
            assertEquals(
                WorkspacePreparationActivity(
                    retryId.value.toString(),
                    WorkspacePreparationActivityOutcome.Blocked(reason),
                ),
                events.last(),
            )
            assertEquals(
                listOf(WorkspacePreparationActivityOutcome.CheckingHost(firstId.value.toString())),
                events
                    .map { it.outcome }
                    .filter {
                        it is WorkspacePreparationActivityOutcome.CheckingHost ||
                            it is WorkspacePreparationActivityOutcome.RetryingHost
                    },
            )
            script.assertDrained()
            assertEquals(0, ids.size)
            preparations.close()
        }
    }

    @Test
    fun `malformed inspection cannot become an admitted retry`() = runTest {
        for (reply in listOf(IdeLifecycleResult.Opened(target), inspected().copy(host = "invalid"))) {
            val script =
                Script(
                    listOf(
                        open(firstId) to IdeLifecycleResult.Blocked(IdeLifecycleFailure.HOST_UNAVAILABLE),
                        WorkspaceLifecycleRequest.Inspect to reply,
                    )
                )
            val ids = ArrayDeque(listOf(firstId, retryId))
            val preparations = WorkspacePreparations(this, script::exchange, newId = { ids.removeFirst() })
            preparations.prepare(root)
            runCurrent()
            val result = async { rejectedDemand(preparations).query(root, ExistingIdeOperation.Status) }
            runCurrent()
            assertEquals(
                WorkspaceDemandCause.Preparation(WorkspacePreparationFailure.RESPONSE_REJECTED),
                operationFailure(result.await()).cause,
            )
            script.assertDrained()
            preparations.close()
        }
    }

    @Test
    fun `reopened project must retain the inspected host and exact root`() = runTest {
        val foreign = target.copy(host = "00000000-0000-0000-0000-000000000005")
        val replies =
            listOf(
                IdeLifecycleResult.Opened(target.copy(root = "/other")),
                IdeLifecycleResult.Opened(foreign),
                IdeLifecycleResult.Pending(retryId.value.toString(), IdeLifecycleStage.IMPORTING, foreign.host),
            )
        for (reply in replies) {
            val script =
                Script(
                    listOf(
                        open(firstId) to IdeLifecycleResult.Blocked(IdeLifecycleFailure.PLUGIN_UNAVAILABLE),
                        WorkspaceLifecycleRequest.Inspect to inspected(),
                        open(retryId) to reply,
                    )
                )
            val ids = ArrayDeque(listOf(firstId, retryId))
            val preparations = WorkspacePreparations(this, script::exchange, newId = { ids.removeFirst() })
            preparations.prepare(root)
            runCurrent()
            val result = async { rejectedDemand(preparations).query(root, ExistingIdeOperation.Status) }
            runCurrent()
            assertEquals(
                WorkspaceDemandCause.Preparation(WorkspacePreparationFailure.RESPONSE_REJECTED),
                operationFailure(result.await()).cause,
            )
            script.assertDrained()
            preparations.close()
        }
    }

    @Test
    fun `recovery identity rejection preserves the prior outcome without another effect`() = runTest {
        val script = Script(listOf(open(firstId) to IdeLifecycleResult.Blocked(IdeLifecycleFailure.HOST_UNAVAILABLE)))
        val preparations = WorkspacePreparations(this, script::exchange, capacity = 1, newId = { firstId })
        val original = (preparations.prepare(root) as Refinement.Refined).value
        runCurrent()
        val result = rejectedDemand(preparations).query(root, ExistingIdeOperation.Status)
        assertEquals(
            WorkspaceDemandResult.Rejected(
                WorkspaceDemandFailure.Admission(root, WorkspacePreparationFailure.IDENTITY_REJECTED)
            ),
            result,
        )
        assertEquals(WorkspacePreparationOutcome.Blocked(IdeLifecycleFailure.HOST_UNAVAILABLE), original.state.value)
        assertSame(original, (preparations.observe(firstId) as Refinement.Refined).value)
        script.assertDrained()
        preparations.close()
    }

    @Test
    fun `inspection deadline remains terminal and cannot repeat the uncertain request`() = runTest {
        val ids = ArrayDeque(listOf(firstId, retryId))
        val requests = mutableListOf<WorkspaceLifecycleRequest>()
        val preparations =
            WorkspacePreparations(
                this,
                { request ->
                    requests += request
                    when (requests.size) {
                        1 -> {
                            assertEquals(open(firstId), request)
                            IdeLifecycleResult.Blocked(IdeLifecycleFailure.PLUGIN_UNAVAILABLE)
                        }
                        2 -> {
                            assertEquals(WorkspaceLifecycleRequest.Inspect, request)
                            awaitCancellation()
                        }
                        else -> error("Unexpected lifecycle exchange")
                    }
                },
                budget = 1.seconds,
                newId = { ids.removeFirst() },
            )
        preparations.prepare(root)
        runCurrent()
        val demand = rejectedDemand(preparations)
        val first = async { demand.query(root, ExistingIdeOperation.Status) }
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        val failure = WorkspaceDemandCause.Preparation(WorkspacePreparationFailure.DEADLINE_EXCEEDED)
        assertEquals(failure, operationFailure(first.await()).cause)
        val repeated = demand.query(root, ExistingIdeOperation.Status)
        assertEquals(failure, operationFailure(repeated).cause)
        assertEquals(2, requests.size)
        preparations.close()
    }

    private fun rejectedDemand(preparations: WorkspacePreparations) =
        PreparedWorkspaceDemand(
            preparations,
            { error("Unexpected admitted-workspace inspection") },
            { _, _ -> error("Unexpected semantic execution") },
        )

    private fun operationFailure(result: WorkspaceDemandResult) =
        (result as WorkspaceDemandResult.Rejected).failure as WorkspaceDemandFailure.Operation

    private fun inspected() =
        IdeLifecycleResult.Inspected(
            target.host,
            "/idea",
            "IU-262.1",
            emptyList(),
            HostedCompatibilityDocument("262.1.1", "262.1.1-IJ", "0.50.0", CanonicalHostedContract.document),
        )

    private fun open(id: WorkspacePreparationId) = WorkspaceLifecycleRequest.Open("/workspace", id.value.toString())

    private fun id(raw: String) = checkNotNull(WorkspacePreparationId.admit(raw))

    private class Script(expectations: List<Pair<WorkspaceLifecycleRequest, IdeLifecycleResult>>) {
        private val remaining = ArrayDeque(expectations)
        private var unexpected = 0

        fun exchange(request: WorkspaceLifecycleRequest): IdeLifecycleResult {
            if (remaining.isEmpty() || remaining.first().first != request) {
                unexpected++
                error("Unexpected lifecycle request: $request")
            }
            return remaining.removeFirst().second
        }

        fun assertDrained() {
            assertEquals(0, unexpected, "Unexpected lifecycle requests")
            assertEquals(0, remaining.size, "Unconsumed lifecycle expectations")
        }
    }
}
