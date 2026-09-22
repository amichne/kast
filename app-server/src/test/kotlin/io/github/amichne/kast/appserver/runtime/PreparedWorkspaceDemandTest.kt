@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectDescription
import io.github.amichne.kast.protocol.contract.IdeProjectOwnership
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreparedWorkspaceDemandTest {
    private val root = CanonicalRoot(Path.of("/workspace"))
    private val target =
        IdeProjectTarget("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002", "/workspace")

    @Test
    fun `semantic demand prepares verifies native identity and sends the operation once`() = runTest {
        val calls = mutableListOf<String>()
        val preparations =
            WorkspacePreparations(
                this,
                {
                    calls += "open"
                    IdeLifecycleResult.Opened(target)
                },
            )
        val native = ExistingIdeExchange.Rejected(ExistingIdeFailure.TRANSPORT_REJECTED)
        val demand =
            PreparedWorkspaceDemand(
                preparations,
                {
                    calls += "inspect"
                    IdeLifecycleResult.Inspected(
                        target.host,
                        "/idea",
                        "IU-262.1",
                        listOf(IdeProjectDescription(target, IdeProjectOwnership.MANAGED, 1)),
                    )
                },
                { prepared, operation ->
                    calls += "query"
                    assertEquals(target, prepared.target)
                    assertEquals(ExistingIdeOperation.Status, operation)
                    native
                },
            )
        val result = async { demand.query(root, ExistingIdeOperation.Status) }
        runCurrent()
        assertEquals(WorkspaceDemandResult.Native(native), result.await())
        assertEquals(listOf("open", "inspect", "query"), calls)
        preparations.close()
    }

    @Test
    fun `blocked preparation preserves exact cause and never inspects or sends semantics`() = runTest {
        val preparations =
            WorkspacePreparations(this, { IdeLifecycleResult.Blocked(IdeLifecycleFailure.TRUST_REQUIRED) })
        val demand =
            PreparedWorkspaceDemand(
                preparations,
                { error("unexpected inspection") },
                { _, _ -> error("unexpected semantic request") },
            )
        val result = async { demand.query(root, ExistingIdeOperation.Status) }
        runCurrent()
        val failure = (result.await() as WorkspaceDemandResult.Rejected).failure as WorkspaceDemandFailure.Operation
        assertEquals(root, failure.root)
        assertEquals(WorkspaceDemandCause.Lifecycle(IdeLifecycleFailure.TRUST_REQUIRED), failure.cause)
        val retained = (preparations.observe(failure.id) as Refinement.Refined).value
        assertEquals(WorkspacePreparationOutcome.Blocked(IdeLifecycleFailure.TRUST_REQUIRED), retained.state.value)
        preparations.close()
    }

    @Test
    fun `cancelled demand leaves preparation available to the next caller`() = runTest {
        val opened = CompletableDeferred<IdeLifecycleResult>()
        var opens = 0
        var queries = 0
        val preparations =
            WorkspacePreparations(
                this,
                {
                    opens++
                    opened.await()
                },
            )
        val demand =
            PreparedWorkspaceDemand(
                preparations,
                { inspected(target) },
                { _, _ ->
                    queries++
                    ExistingIdeExchange.Rejected(ExistingIdeFailure.TRANSPORT_REJECTED)
                },
            )
        val first = async { demand.query(root, ExistingIdeOperation.Status) }
        runCurrent()
        first.cancel()
        runCurrent()
        val second = async { demand.query(root, ExistingIdeOperation.Status) }
        opened.complete(IdeLifecycleResult.Opened(target))
        runCurrent()
        assertEquals(
            WorkspaceDemandResult.Native(ExistingIdeExchange.Rejected(ExistingIdeFailure.TRANSPORT_REJECTED)),
            second.await(),
        )
        assertEquals(1, opens)
        assertEquals(1, queries)
        preparations.close()
    }

    @Test
    fun `changed host rejects current demand and retains history while next demand prepares anew`() = runTest {
        val changed =
            target.copy(host = "00000000-0000-0000-0000-000000000003", project = "00000000-0000-0000-0000-000000000004")
        var opens = 0
        var queries = 0
        val preparations =
            WorkspacePreparations(
                this,
                {
                    opens++
                    IdeLifecycleResult.Opened(if (opens == 1) target else changed)
                },
                capacity = 2,
            )
        val demand =
            PreparedWorkspaceDemand(
                preparations,
                { inspected(changed) },
                { workspace, _ ->
                    queries++
                    assertEquals(changed, workspace.target)
                    ExistingIdeExchange.Rejected(ExistingIdeFailure.TRANSPORT_REJECTED)
                },
            )
        val first = async { demand.query(root, ExistingIdeOperation.Status) }
        runCurrent()
        val failure = (first.await() as WorkspaceDemandResult.Rejected).failure as WorkspaceDemandFailure.Operation
        assertEquals(WorkspaceDemandCause.HostChanged, failure.cause)
        assertEquals(0, queries)
        val history = (preparations.observe(failure.id) as Refinement.Refined).value
        assertEquals(target, (history.state.value as WorkspacePreparationOutcome.Complete).workspace.target)
        val second = async { demand.query(root, ExistingIdeOperation.Status) }
        runCurrent()
        assertEquals(
            WorkspaceDemandResult.Native(ExistingIdeExchange.Rejected(ExistingIdeFailure.TRANSPORT_REJECTED)),
            second.await(),
        )
        assertEquals(2, opens)
        assertEquals(1, queries)
        assertEquals(
            Refinement.Rejected(WorkspacePreparationFailure.CAPACITY_EXCEEDED),
            preparations.prepare(CanonicalRoot(Path.of("/other"))),
        )
        preparations.close()
    }

    private fun inspected(value: IdeProjectTarget) =
        IdeLifecycleResult.Inspected(
            value.host,
            "/idea",
            "IU-262.1",
            listOf(IdeProjectDescription(value, IdeProjectOwnership.MANAGED, 1)),
        )
}
