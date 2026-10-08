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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class WorkspacePreparationRetentionTest {
    private val root = CanonicalRoot(Path.of("/workspace"))
    private val target =
        IdeProjectTarget("00000000-0000-0000-0000-000000000003", "00000000-0000-0000-0000-000000000004", "/workspace")

    @Test
    fun `one root recovers after more than 256 unavailable demands with bounded history and no semantic replay`() =
        runTest {
            val reasons =
                listOf(
                    IdeLifecycleFailure.HOST_UNAVAILABLE,
                    IdeLifecycleFailure.PLUGIN_UNAVAILABLE,
                    IdeLifecycleFailure.COMPATIBILITY_REJECTED,
                )
            for (capacity in listOf(1, 256)) for (reason in reasons) {
                val case = RetryCase(scope = this, reason = reason, capacity = capacity)
                val original = (case.preparations.prepare(root) as Refinement.Refined).value
                runCurrent()
                val originalEvents = case.events.toList()
                repeat(case.retryCount) {
                    val admitted = case.preparations.prepareForDemand(root)
                    assertTrue(admitted is Refinement.Refined, "Expected admitted retry, received $admitted")
                    val retried = (admitted as Refinement.Refined).value
                    runCurrent()
                    assertEquals(WorkspacePreparationOutcome.Blocked(reason), retried.state.value)
                    assertSame(retried, (case.preparations.observe(retried.id) as Refinement.Refined).value)
                }
                val result = async { case.demand.query(root, ExistingIdeOperation.Status) }
                runCurrent()

                assertEquals(WorkspaceDemandResult.Native(case.native), result.await())
                assertEquals(1, case.queries)
                assertEquals(1, case.inspections)
                assertEquals(case.retryCount + 2, case.issued)
                assertEquals(WorkspacePreparationOutcome.Blocked(reason), original.state.value)
                assertEquals(originalEvents, case.events.filter { it.requestId == original.id.value.toString() })
                assertEquals(
                    Refinement.Rejected(WorkspacePreparationFailure.UNKNOWN_OPERATION),
                    case.preparations.observe(original.id),
                )
                assertEquals(listOf(root.path.toString()), case.preparations.activeRoots())
                if (capacity == 1)
                    assertEquals(
                        Refinement.Rejected(WorkspacePreparationFailure.CAPACITY_EXCEEDED),
                        case.preparations.prepare(CanonicalRoot(Path.of("/third"))),
                    )
                case.assertDrained()
                case.preparations.close()
            }
        }

    @Test
    fun `recovery at full capacity protects another current root and its own pending retry`() = runTest {
        val other = CanonicalRoot(Path.of("/other"))
        val otherTarget = target.copy(root = other.path.toString())
        val release = CompletableDeferred<Unit>()
        val script =
            Script(
                listOf(
                    open(other, id(1)) to IdeLifecycleResult.Opened(otherTarget),
                    open(root, id(2)) to IdeLifecycleResult.Blocked(IdeLifecycleFailure.HOST_UNAVAILABLE),
                    WorkspaceLifecycleRequest.Inspect to inspected(),
                    open(root, id(3)) to IdeLifecycleResult.Opened(target),
                ),
                beforeInspection = { release.await() },
            )
        var issued = 0
        val preparations =
            WorkspacePreparations(scope = this, exchange = script::exchange, capacity = 2, newId = { id(++issued) })
        val retained = (preparations.prepare(other) as Refinement.Refined).value
        val original = (preparations.prepare(root) as Refinement.Refined).value
        runCurrent()

        val retry = (preparations.prepareForDemand(root) as Refinement.Refined).value
        runCurrent()

        assertSame(retained, (preparations.observe(retained.id) as Refinement.Refined).value)
        assertSame(retained, (preparations.prepare(other) as Refinement.Refined).value)
        assertSame(retry, (preparations.prepareForDemand(root) as Refinement.Refined).value)
        assertEquals(setOf(UpgradeBlocker.PREPARATION_ACTIVE), preparations.upgradeBlockers())
        assertEquals(
            Refinement.Rejected(WorkspacePreparationFailure.CAPACITY_EXCEEDED),
            preparations.prepare(CanonicalRoot(Path.of("/third"))),
        )
        assertEquals(WorkspacePreparationOutcome.Blocked(IdeLifecycleFailure.HOST_UNAVAILABLE), original.state.value)
        release.complete(Unit)
        runCurrent()
        assertEquals(target, (retry.state.value as WorkspacePreparationOutcome.Complete).workspace.target)
        assertEquals(listOf("/other", "/workspace"), preparations.activeRoots())
        script.assertDrained()
        preparations.close()
    }

    @Test
    fun `a retired ready record yields bounded capacity without changing the held readiness proof`() = runTest {
        val other = CanonicalRoot(Path.of("/other"))
        val script =
            Script(
                listOf(
                    open(root, id(1)) to IdeLifecycleResult.Opened(target),
                    open(other, id(2)) to IdeLifecycleResult.Opened(target.copy(root = "/other")),
                )
            )
        var issued = 0
        val preparations =
            WorkspacePreparations(scope = this, exchange = script::exchange, capacity = 1, newId = { id(++issued) })
        val original = (preparations.prepare(root) as Refinement.Refined).value
        runCurrent()
        val outcome = original.state.value as WorkspacePreparationOutcome.Complete
        preparations.invalidate(original.id, outcome.workspace)

        val next = (preparations.prepare(other) as Refinement.Refined).value
        runCurrent()

        assertSame(outcome, original.state.value)
        assertEquals(
            Refinement.Rejected(WorkspacePreparationFailure.UNKNOWN_OPERATION),
            preparations.observe(original.id),
        )
        assertSame(next, (preparations.observe(next.id) as Refinement.Refined).value)
        preparations.invalidate(original.id, outcome.workspace)
        assertSame(next, (preparations.prepare(other) as Refinement.Refined).value)
        script.assertDrained()
        preparations.close()
    }

    private inner class RetryCase(scope: CoroutineScope, reason: IdeLifecycleFailure, capacity: Int) {
        val retryCount = 300
        private val script =
            Script(
                listOf(open(root, id(1)) to IdeLifecycleResult.Blocked(reason)) +
                    List(retryCount) { WorkspaceLifecycleRequest.Inspect to IdeLifecycleResult.Blocked(reason) } +
                    listOf(
                        WorkspaceLifecycleRequest.Inspect to inspected(),
                        open(root, id(retryCount + 2)) to IdeLifecycleResult.Opened(target),
                    )
            )
        var issued = 0
            private set

        val events = mutableListOf<WorkspacePreparationActivity>()
        val preparations =
            WorkspacePreparations(
                scope = scope,
                exchange = script::exchange,
                observer = WorkspacePreparationObserver { events += it },
                capacity = capacity,
                newId = { id(++issued) },
            )
        val native = ExistingIdeExchange.Rejected(ExistingIdeFailure.TRANSPORT_REJECTED)
        var queries = 0
            private set

        var inspections = 0
            private set

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

        fun assertDrained() = script.assertDrained()
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

    private fun open(root: CanonicalRoot, id: WorkspacePreparationId) =
        WorkspaceLifecycleRequest.Open(root.path.toString(), id.value.toString())

    private fun id(number: Int) =
        checkNotNull(WorkspacePreparationId.admit("00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"))

    private class Script(
        expectations: List<Pair<WorkspaceLifecycleRequest, IdeLifecycleResult>>,
        private val beforeInspection: suspend () -> Unit = {},
    ) {
        private val remaining = ArrayDeque(expectations)
        private var unexpected = 0

        suspend fun exchange(request: WorkspaceLifecycleRequest): IdeLifecycleResult {
            if (remaining.isEmpty() || remaining.first().first != request) {
                unexpected++
                error("Unexpected lifecycle request")
            }
            val reply = remaining.removeFirst().second
            if (request == WorkspaceLifecycleRequest.Inspect) beforeInspection()
            return reply
        }

        fun assertDrained() {
            assertEquals(0, unexpected, "Unexpected lifecycle requests")
            assertEquals(0, remaining.size, "Unconsumed lifecycle expectations")
        }
    }
}
