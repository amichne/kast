@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.HostedContractDocument
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import java.nio.file.Path
import kotlinx.coroutines.async
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
    private val laterId = id("00000000-0000-0000-0000-000000000005")
    private val target =
        IdeProjectTarget("00000000-0000-0000-0000-000000000003", "00000000-0000-0000-0000-000000000004", "/workspace")

    @Test
    fun `nonavailability failures never automatically replay and later demands observe current native refusal`() =
        runTest {
            val excluded =
                setOf(
                    IdeLifecycleFailure.HOST_UNAVAILABLE,
                    IdeLifecycleFailure.PLUGIN_UNAVAILABLE,
                    IdeLifecycleFailure.COMPATIBILITY_REJECTED,
                )
            for (reason in IdeLifecycleFailure.entries.filterNot { it in excluded }) {
                val script =
                    Script(
                        listOf(
                            open(firstId) to IdeLifecycleResult.Blocked(reason),
                            WorkspaceLifecycleRequest.Inspect to IdeLifecycleResult.Blocked(reason),
                            WorkspaceLifecycleRequest.Inspect to IdeLifecycleResult.Blocked(reason),
                        )
                    )
                val ids = ArrayDeque(listOf(firstId, retryId, laterId))
                val preparations = WorkspacePreparations(this, script::exchange, newId = { ids.removeFirst() })
                val entry = (preparations.prepare(root) as Refinement.Refined).value
                runCurrent()
                val held = entry.state.value
                assertEquals(WorkspacePreparationOutcome.Blocked(reason), held)
                advanceTimeBy(1000)
                runCurrent()
                assertEquals(1, script.exchanges, "terminal failure alone must not schedule recovery")
                val demand = rejectedDemand(preparations)
                for ((index, expectedId) in listOf(retryId, laterId).withIndex()) {
                    val result = async { demand.query(root, ExistingIdeOperation.Status) }
                    runCurrent()
                    val failure = operationFailure(result.await())
                    assertEquals(expectedId, failure.id)
                    assertEquals(WorkspaceDemandCause.Lifecycle(reason), failure.cause)
                    assertEquals(index + 2, script.exchanges, "one current observation per authorized demand")
                    assertSame(held, entry.state.value)
                }
                assertSame(entry, (preparations.observe(firstId) as Refinement.Refined).value)
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
                    scope = this,
                    exchange = script::exchange,
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
                    .filter { outcome ->
                        outcome is WorkspacePreparationActivityOutcome.CheckingHost ||
                            outcome is WorkspacePreparationActivityOutcome.RetryingHost
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
        val preparations =
            WorkspacePreparations(scope = this, exchange = script::exchange, capacity = 1, newId = { firstId })
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
            host = target.host,
            home = "/idea",
            build = "IU-262.1",
            projects = emptyList(),
            compatibility =
                HostedCompatibilityDocument(
                    ideBuild = "262.1.1",
                    kotlinPluginBuild = "262.1.1-IJ",
                    hostedPluginVersion = "0.50.0",
                    hostedContract =
                        HostedContractDocument(
                            runtimeProtocolIdentity = "kast.ide-hosted.runtime.v3",
                            operationRegistryDigest = "a".repeat(64),
                            wireSchemaDigest = "b".repeat(64),
                            capabilities = listOf("query.run"),
                        ),
                ),
        )

    private fun open(id: WorkspacePreparationId) = WorkspaceLifecycleRequest.Open("/workspace", id.value.toString())

    private fun id(raw: String) = checkNotNull(WorkspacePreparationId.admit(raw))

    private class Script(expectations: List<Pair<WorkspaceLifecycleRequest, IdeLifecycleResult>>) {
        private val remaining = ArrayDeque(expectations)
        private var unexpected = 0
        var exchanges = 0
            private set

        fun exchange(request: WorkspaceLifecycleRequest): IdeLifecycleResult {
            if (remaining.isEmpty() || remaining.first().first != request) {
                unexpected++
                error("Unexpected lifecycle request: $request")
            }
            exchanges++
            return remaining.removeFirst().second
        }

        fun assertDrained() {
            assertEquals(0, unexpected, "Unexpected lifecycle requests")
            assertEquals(0, remaining.size, "Unconsumed lifecycle expectations")
        }
    }
}
