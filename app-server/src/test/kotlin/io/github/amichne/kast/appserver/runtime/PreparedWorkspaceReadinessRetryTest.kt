package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.DaemonOperationCall
import io.github.amichne.kast.appserver.ide.ExistingIdeClassName
import io.github.amichne.kast.appserver.ide.ExistingIdeDescriptor
import io.github.amichne.kast.appserver.ide.ExistingIdeDocuments
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.HostedEndpointOwnerPid
import io.github.amichne.kast.appserver.ide.HostedMutationOperation
import io.github.amichne.kast.appserver.ide.HostedPlanIdentity
import io.github.amichne.kast.appserver.mcpOperation
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.distribution.managed.canonicalRootFixture
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectDescription
import io.github.amichne.kast.protocol.contract.IdeProjectOwnership
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import io.github.amichne.kast.protocol.wire.presentation.OperationPreparation
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PreparedWorkspaceReadinessRetryTest {
    @Test
    fun `prepared read reloads once and retains the exact target`() = runTest {
        val fixture = RetryCase()
        val preparations = WorkspacePreparations(this, fixture::open)
        try {
            val rejection = fixture.decode(ModelReloadDocument())
            val demand =
                PreparedWorkspaceDemand(preparations, { fixture.inspected() }) { workspace, operation ->
                    fixture.invocations++
                    assertEquals(fixture.target, workspace.target)
                    assertSame(fixture.read, operation)
                    if (fixture.invocations == 1) rejection else fixture.completed
                }
            val ready = (demand.prepare(fixture.root) as Refinement.Refined).value
            assertEquals(WorkspaceDemandResult.Native(fixture.completed), ready.query(fixture.root, fixture.read))
            assertEquals(2, fixture.opens)
            assertEquals(2, fixture.invocations)
        } finally {
            preparations.close()
        }
    }

    @Test
    fun `prepared reads retry only moved freshness at model capture or content revalidation`() = runTest {
        for (stage in listOf("MODEL_CAPTURE", "CONTENT_REVALIDATION")) {
            val fixture = RetryCase()
            val preparations = WorkspacePreparations(this, fixture::open)
            try {
                val rejection = fixture.decode(FreshnessDocument(stage = stage))
                val demand =
                    PreparedWorkspaceDemand(preparations, { fixture.inspected() }) { workspace, _ ->
                        assertEquals(fixture.target, workspace.target)
                        if (++fixture.invocations == 1) rejection else fixture.completed
                    }
                val ready = (demand.prepare(fixture.root) as Refinement.Refined).value
                assertEquals(WorkspaceDemandResult.Native(fixture.completed), ready.query(fixture.root, fixture.read))
                assertEquals(2, fixture.invocations)
            } finally {
                preparations.close()
            }
        }
    }

    @Test
    fun `retry exhaustion preserves the second rejection without a third invocation`() = runTest {
        val fixture = RetryCase()
        val preparations = WorkspacePreparations(this, fixture::open)
        try {
            val first = fixture.decode(ModelReloadDocument())
            val rejection = fixture.decode(FreshnessDocument())
            val demand =
                PreparedWorkspaceDemand(preparations, { fixture.inspected() }) { _, _ ->
                    if (++fixture.invocations == 1) first else rejection
                }
            val ready = (demand.prepare(fixture.root) as Refinement.Refined).value
            assertEquals(WorkspaceDemandResult.Native(rejection), ready.query(fixture.root, fixture.read))
            assertEquals(2, fixture.invocations)
            assertEquals(2, fixture.opens)
        } finally {
            preparations.close()
        }
    }

    @Test
    fun `unbound readiness retry must not fall through to a replacement host after an operation was sent`() = runTest {
        val fixture = RetryCase()
        var observed = fixture.target
        val changed = fixture.target.copy(host = "00000000-0000-0000-0000-000000000003")
        val preparations =
            WorkspacePreparations(
                this,
                {
                    if (++fixture.opens > 1) observed = changed
                    IdeLifecycleResult.Opened(observed)
                },
            )
        try {
            val rejection = fixture.decode(ModelReloadDocument())
            val demand =
                PreparedWorkspaceDemand(preparations, { fixture.inspected(observed) }) { workspace, _ ->
                    fixture.invocations++
                    assertEquals(fixture.target, workspace.target)
                    rejection
                }
            val result = demand.query(fixture.root, fixture.read) as WorkspaceDemandResult.Rejected
            assertEquals(WorkspaceDemandCause.HostChanged, (result.failure as WorkspaceDemandFailure.Operation).cause)
            assertEquals(1, fixture.invocations)
            assertEquals(2, fixture.opens)
        } finally {
            preparations.close()
        }
    }

    @Test
    fun `three concurrent prepared public reads recover once from overlapping cold refreshes`() = runTest {
        val fixture = RetryCase()
        val preparations = WorkspacePreparations(this, fixture::open)
        try {
            val identity = PublicToolIdentity.QUERY_SYMBOLS
            val arguments = PublicToolContract.examples(identity).examples.getValue("runByName").value
            val admitted = (PublicToolContract.admit(identity, arguments) as Refinement.Refined).value
            val operations =
                List(3) { (mcpOperation(DaemonOperationCall.PublicTool(admitted)) as Refinement.Refined).value }
            val attempts = mutableMapOf<ExistingIdeOperation, Int>()
            val firstAttempts = CompletableDeferred<Unit>()
            val rejection = fixture.decode(FreshnessDocument(), operations.first())
            val demand =
                PreparedWorkspaceDemand(preparations, { fixture.inspected() }) { workspace, operation ->
                    assertEquals(fixture.target, workspace.target)
                    val attempt = attempts.getOrDefault(operation, 0) + 1
                    attempts[operation] = attempt
                    if (attempts.size == 3) firstAttempts.complete(Unit)
                    if (attempt == 1) {
                        firstAttempts.await()
                        rejection
                    } else fixture.completed
                }
            val ready = operations.map { (demand.prepare(fixture.root) as Refinement.Refined).value }
            assertEquals(1, fixture.opens)
            val results =
                operations
                    .mapIndexed { index, operation -> async { ready[index].query(fixture.root, operation) } }
                    .awaitAll()
            assertEquals(List(3) { WorkspaceDemandResult.Native(fixture.completed) }, results)
            assertEquals(List(3) { 2 }, operations.map { attempts.getValue(it) })
            assertEquals(2, fixture.opens)
        } finally {
            preparations.close()
        }
    }

    @Test
    fun `readiness retry never retargets a retired captured host`() = runTest {
        val fixture = RetryCase()
        val changed = fixture.target.copy(host = "00000000-0000-0000-0000-000000000003")
        val preparations =
            WorkspacePreparations(
                this,
                {
                    fixture.opens++
                    IdeLifecycleResult.Opened(if (fixture.opens == 1) fixture.target else changed)
                },
            )
        try {
            val rejection = fixture.decode(ModelReloadDocument())
            val demand =
                PreparedWorkspaceDemand(preparations, { fixture.inspected() }) { _, _ ->
                    fixture.invocations++
                    rejection
                }
            val ready = (demand.prepare(fixture.root) as Refinement.Refined).value
            val result = ready.query(fixture.root, fixture.read) as WorkspaceDemandResult.Rejected
            assertEquals(WorkspaceDemandCause.HostChanged, (result.failure as WorkspaceDemandFailure.Operation).cause)
            assertEquals(1, fixture.invocations)
            assertEquals(2, fixture.opens)
        } finally {
            preparations.close()
        }
    }

    @Test
    fun `mutations and other freshness failures are never replayed`() = runTest {
        val fixture = RetryCase()
        val identity = "plan:" + "a".repeat(64)
        val request =
            (canonicalCliRequestPreparers()
                    .changeApply
                    .prepare(ChangeApplyRequest((ProtocolText.parse(identity) as Refinement.Refined).value))
                    as OperationPreparation.Prepared)
                .request
        val mutation =
            (ExistingIdeOperation.Mutation.admit(
                    request,
                    HostedMutationOperation.CHANGE_APPLY,
                    (HostedPlanIdentity.parse(identity) as Refinement.Refined).value,
                ) as Refinement.Refined)
                .value
        val cases =
            listOf(
                mutation to fixture.decode(ModelReloadDocument()),
                mutation to fixture.decode(FreshnessDocument()),
                fixture.read to fixture.decode(FreshnessDocument(detail = "INDEXING")),
                fixture.read to fixture.decode(FreshnessDocument(stage = "SEMANTIC_READ")),
                fixture.read to fixture.decode(FreshnessDocument(failure = "CANCELLED")),
                ExistingIdeOperation.Status to fixture.decode(ModelReloadDocument()),
            )
        for ((operation, rejection) in cases) {
            val preparations = WorkspacePreparations(this, fixture::open)
            try {
                fixture.invocations = 0
                val demand =
                    PreparedWorkspaceDemand(preparations, { fixture.inspected() }) { _, _ ->
                        fixture.invocations++
                        rejection
                    }
                val ready = (demand.prepare(fixture.root) as Refinement.Refined).value
                assertEquals(WorkspaceDemandResult.Native(rejection), ready.query(fixture.root, operation))
                assertEquals(1, fixture.invocations)
            } finally {
                preparations.close()
            }
        }
    }

    @Test
    fun `cancellation during retry propagates without another preparation or invocation`() = runTest {
        val fixture = RetryCase()
        val preparations = WorkspacePreparations(this, fixture::open)
        try {
            val rejection = fixture.decode(ModelReloadDocument())
            val cancelled = CancellationException("caller cancelled")
            val demand =
                PreparedWorkspaceDemand(preparations, { fixture.inspected() }) { _, _ ->
                    if (++fixture.invocations == 1) rejection else throw cancelled
                }
            val ready = (demand.prepare(fixture.root) as Refinement.Refined).value
            try {
                ready.query(fixture.root, fixture.read)
                error("Expected cancellation")
            } catch (observed: CancellationException) {
                assertSame(cancelled, observed)
            }
            assertEquals(2, fixture.invocations)
            assertEquals(2, fixture.opens)
        } finally {
            preparations.close()
        }
    }
}

private class RetryCase {
    val root = canonicalRootFixture(Path.of("/workspace"))
    val target =
        IdeProjectTarget("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002", "/workspace")
    val read = ExistingIdeOperation.Classes((ExistingIdeClassName.parse("Subject") as Refinement.Refined).value)
    val completed = ExistingIdeExchange.Rejected(ExistingIdeFailure.TRANSPORT_REJECTED)
    var opens = 0
    var invocations = 0

    fun open(request: io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest): IdeLifecycleResult {
        check(request is io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest.Open)
        opens++
        return IdeLifecycleResult.Opened(target)
    }

    fun inspected(observed: IdeProjectTarget = target): IdeLifecycleResult =
        IdeLifecycleResult.Inspected(
            host = observed.host,
            home = "/idea",
            build = "IU-262.1",
            projects = listOf(IdeProjectDescription(observed, IdeProjectOwnership.MANAGED, 1)),
            compatibility =
                HostedCompatibilityDocument("262.1.1", "262.1.1-IJ", "0.49.0", CanonicalHostedContract.document),
        )

    inline fun <reified Document> decode(
        document: Document,
        operation: ExistingIdeOperation = read,
    ): ExistingIdeExchange.HostRejected =
        ExistingIdeDocuments.response(
            Json { encodeDefaults = true }.encodeToString(document).toByteArray(),
            root,
            operation,
            ExistingIdeDescriptor(
                (HostedEndpointOwnerPid.parse("1") as Refinement.Refined).value,
                UUID.fromString(target.host),
            ),
        ) as ExistingIdeExchange.HostRejected
}

@Serializable
private data class ModelReloadDocument(
    val type: String = "HOST_REJECTED",
    val failure: String = "MODEL_REFRESH_REQUIRED",
)

@Serializable
private data class FreshnessDocument(
    val schemaVersion: Int = 1,
    val outcome: String = "rejected",
    val failure: String = "FRESHNESS_REJECTED",
    val detail: String = "MOVED",
    val stage: String = "CONTENT_REVALIDATION",
)
