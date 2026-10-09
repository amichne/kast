@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectDescription
import io.github.amichne.kast.protocol.contract.IdeProjectOwnership
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class WorkspaceAvailabilityRecoveryTest {
    @Test
    fun `one persistent owner recovers an unavailable host before sending the query once`() = runTest {
        for (reason in
            listOf(
                IdeLifecycleFailure.HOST_UNAVAILABLE,
                IdeLifecycleFailure.PLUGIN_UNAVAILABLE,
                IdeLifecycleFailure.COMPATIBILITY_REJECTED,
            )) {
            val case = RecoveryCase(this, reason)
            val original = (case.preparations.prepare(case.root) as Refinement.Refined).value
            runCurrent()
            assertEquals(WorkspacePreparationOutcome.Blocked(reason), original.state.value)
            val result = async { case.demand.query(case.root, ExistingIdeOperation.Status) }
            runCurrent()
            assertEquals(WorkspaceDemandResult.Native(case.native), result.await())
            assertEquals(WorkspacePreparationOutcome.Blocked(reason), original.state.value)
            assertEquals(original, (case.preparations.observe(case.originalId) as Refinement.Refined).value)
            assertEquals(listOf("/workspace"), case.preparations.activeRoots())
            case.assertDrained(1)
            case.preparations.close()
        }
    }

    @Test
    fun `concurrent demand shares recovery and cancelling a waiter leaves the retry available`() = runTest {
        val releaseInspection = CompletableDeferred<Unit>()
        val case = RecoveryCase(this, IdeLifecycleFailure.PLUGIN_UNAVAILABLE) { releaseInspection.await() }
        val original = (case.preparations.prepare(case.root) as Refinement.Refined).value
        runCurrent()
        val cancelled = async { case.demand.query(case.root, ExistingIdeOperation.Status) }
        val resumed = async { case.demand.query(case.root, ExistingIdeOperation.Status) }
        runCurrent()
        cancelled.cancel()
        runCurrent()
        releaseInspection.complete(Unit)
        runCurrent()
        assertEquals(WorkspaceDemandResult.Native(case.native), resumed.await())
        assertEquals(WorkspacePreparationOutcome.Blocked(IdeLifecycleFailure.PLUGIN_UNAVAILABLE), original.state.value)
        case.assertDrained(1)
        assertEquals(
            listOf(
                WorkspacePreparationActivityOutcome.CheckingHost(case.originalId.value.toString()),
                WorkspacePreparationActivityOutcome.RetryingHost(case.originalId.value.toString()),
            ),
            case.events
                .map { it.outcome }
                .filter { outcome ->
                    outcome is WorkspacePreparationActivityOutcome.CheckingHost ||
                        outcome is WorkspacePreparationActivityOutcome.RetryingHost
                },
        )
        case.preparations.close()
    }

    private class RecoveryCase(
        scope: CoroutineScope,
        reason: IdeLifecycleFailure,
        beforeInspection: suspend () -> Unit = {},
    ) {
        val root = CanonicalRoot(Path.of("/workspace"))
        val originalId = id("00000000-0000-0000-0000-000000000001")
        private val retryId = id("00000000-0000-0000-0000-000000000002")
        private val ids = ArrayDeque(listOf(originalId, retryId))
        private val target =
            IdeProjectTarget(
                "00000000-0000-0000-0000-000000000003",
                "00000000-0000-0000-0000-000000000004",
                "/workspace",
            )
        private val script =
            LifecycleScript(
                listOf(
                    WorkspaceLifecycleRequest.Open("/workspace", originalId.value.toString()) to
                        IdeLifecycleResult.Blocked(reason),
                    WorkspaceLifecycleRequest.Inspect to inspected(),
                    WorkspaceLifecycleRequest.Open("/workspace", retryId.value.toString()) to
                        IdeLifecycleResult.Opened(target),
                ),
                beforeInspection,
            )
        val events = mutableListOf<WorkspacePreparationActivity>()
        val preparations =
            WorkspacePreparations(
                scope = scope,
                exchange = script::exchange,
                observer = WorkspacePreparationObserver { events += it },
                newId = { ids.removeFirst() },
            )
        val native = ExistingIdeExchange.Rejected(ExistingIdeFailure.TRANSPORT_REJECTED)
        private var inspections = 0
        private var queries = 0
        val demand =
            PreparedWorkspaceDemand(
                preparations,
                {
                    inspections++
                    inspected()
                },
                { workspace, operation ->
                    queries++
                    assertEquals(target, workspace.target)
                    assertEquals(ExistingIdeOperation.Status, operation)
                    native
                },
            )

        fun assertDrained(expectedQueries: Int) {
            script.assertDrained()
            assertEquals(0, ids.size, "Unconsumed preparation identities")
            assertEquals(expectedQueries, inspections)
            assertEquals(expectedQueries, queries)
        }

        private fun inspected() =
            IdeLifecycleResult.Inspected(
                host = target.host,
                home = "/idea",
                build = "IU-262.1",
                projects = listOf(IdeProjectDescription(target, IdeProjectOwnership.MANAGED, 1)),
                compatibility =
                    HostedCompatibilityDocument(
                        ideBuild = "262.1.1",
                        kotlinPluginBuild = "262.1.1-IJ",
                        hostedPluginVersion = "0.50.0",
                        hostedContract = CanonicalHostedContract.document,
                    ),
            )

        private fun id(raw: String) = checkNotNull(WorkspacePreparationId.admit(raw))
    }

    private class LifecycleScript(
        expectations: List<Pair<WorkspaceLifecycleRequest, IdeLifecycleResult>>,
        private val beforeInspection: suspend () -> Unit,
    ) {
        private val remaining = ArrayDeque(expectations)
        private var unexpected = 0

        suspend fun exchange(request: WorkspaceLifecycleRequest): IdeLifecycleResult {
            if (remaining.isEmpty() || remaining.first().first != request) {
                unexpected++
                error("Unexpected lifecycle request: $request")
            }
            val response = remaining.removeFirst().second
            if (request == WorkspaceLifecycleRequest.Inspect) beforeInspection()
            return response
        }

        fun assertDrained() {
            assertEquals(0, unexpected, "Unexpected lifecycle requests")
            assertEquals(0, remaining.size, "Unconsumed lifecycle expectations")
        }
    }
}
