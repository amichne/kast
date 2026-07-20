package io.github.amichne.kast.server.mutation

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.mutation.KastMutationEditApplicationState
import io.github.amichne.kast.api.contract.mutation.KastMutationIdempotencyKey
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationId
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationSelector
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationState
import io.github.amichne.kast.api.contract.mutation.KastMutationProgressStage
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutation
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutationResult
import io.github.amichne.kast.api.contract.mutation.KastWorkspaceTaskId
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.FileAnalysisStatus
import io.github.amichne.kast.api.contract.skill.KastAddFileRequest
import io.github.amichne.kast.api.contract.skill.KastDiagnosticsSummary
import io.github.amichne.kast.api.contract.skill.KastScopeMutationOperation
import io.github.amichne.kast.api.contract.skill.KastScopeMutationSuccessResponse
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.AnalysisException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.server.mutation.coordination.MutationFinishBarrierRequest
import io.github.amichne.kast.server.mutation.coordination.MutationFinishBarrierState
import io.github.amichne.kast.server.mutation.coordination.MutationFinishCoordinationToken
import io.github.amichne.kast.server.mutation.coordination.MutationPathScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

class MutationOperationRegistryTest {
    @TempDir
    lateinit var tempDir: Path

    private val firstOperationId = KastMutationOperationId("00000000-0000-0000-0000-000000000001")
    private val workspaceTaskId = KastWorkspaceTaskId("00000000-0000-0000-0000-000000000420")

    @Test
    fun `same key and fingerprint execute once while different fingerprint conflicts`() = runBlocking {
        val executionCount = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val registry = registry()
        val mutation = addFileMutation("issue-333-retry", "/workspace/Added.kt")
        val fingerprint = MutationFingerprint("same-request")

        val first = registry.submit(mutation, fingerprint) {
            executionCount.incrementAndGet()
            started.complete(Unit)
            release.await()
            MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
        }
        started.await()
        val retry = registry.submit(mutation, fingerprint) {
            error("A deduplicated operation must not install another worker")
        }

        assertEquals(firstOperationId, first.operation.operationId)
        assertEquals(first.operation.operationId, retry.operation.operationId)
        assertFalse(first.deduplicated)
        assertTrue(retry.deduplicated)
        assertEquals(1, executionCount.get())
        assertThrows<ConflictException> {
            registry.submit(
                addFileMutation("issue-333-retry", "/workspace/Different.kt"),
                MutationFingerprint("different-request"),
            ) { MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess()) }
        }

        release.complete(Unit)
        val terminal = awaitTerminal(registry, KastMutationOperationSelector.ByOperationId(firstOperationId))
        assertTrue(terminal.state is KastMutationOperationState.Completed)
        assertEquals(
            terminal,
            registry.status(KastMutationOperationSelector.ByIdempotencyKey(mutation.idempotencyKey)),
        )
    }

    @Test
    fun `same idempotency key starts a new operation in a later workspace task`() = runBlocking {
        val operationIds = ArrayDeque(
            listOf(
                KastMutationOperationId("00000000-0000-0000-0000-000000000001"),
                KastMutationOperationId("00000000-0000-0000-0000-000000000002"),
            ),
        )
        val registry = MutationOperationRegistry(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            workspaceRoot = workspaceRoot(),
            operationIdFactory = operationIds::removeFirst,
        )
        val firstMutation = addFileMutation("issue-420-reused-key", "/workspace/Added.kt")
        val first = registry.submit(firstMutation, MutationFingerprint("same-request")) {
            MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
        }
        awaitTerminal(registry, KastMutationOperationSelector.ByOperationId(first.operation.operationId))

        val secondMutation = firstMutation.copy(
            workspaceTaskId = KastWorkspaceTaskId("00000000-0000-0000-0000-000000000421"),
        )
        val second = registry.submit(secondMutation, MutationFingerprint("same-request")) {
            MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
        }

        assertFalse(second.deduplicated)
        assertNotEquals(first.operation.operationId, second.operation.operationId)
        assertEquals(
            second.operation.operationId,
            registry.status(KastMutationOperationSelector.ByIdempotencyKey(secondMutation.idempotencyKey)).operationId,
        )
    }

    @Test
    fun `path scopes canonicalize symlink aliases and reject workspace escapes`() {
        val root = tempDir.resolve("workspace")
        val realDirectory = root.resolve("real")
        Files.createDirectories(realDirectory)
        Files.createSymbolicLink(root.resolve("alias"), realDirectory)
        val workspaceRoot = NormalizedPath.of(root)

        val realScope = MutationPathScope.parse(workspaceRoot, listOf(realDirectory.resolve("Added.kt").toString()))
        val aliasScope = MutationPathScope.parse(workspaceRoot, listOf(root.resolve("alias/Added.kt").toString()))

        assertTrue(realScope.overlaps(aliasScope))
        assertThrows<ValidationException> {
            MutationPathScope.parse(workspaceRoot, listOf(tempDir.resolve("Outside.kt").toString()))
        }
    }

    @Test
    fun `finish coordination tokens require canonical UUID spelling`() {
        assertThrows<IllegalArgumentException> {
            MutationFinishCoordinationToken("1-1-1-1-1")
        }
    }

    @Test
    fun `path conflicts are FIFO while disjoint operations run concurrently`() = runBlocking {
        val firstMayResolve = CompletableDeferred<Unit>()
        val firstAdmitted = CompletableDeferred<Unit>()
        val secondAdmitted = CompletableDeferred<Unit>()
        val disjointAdmitted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseRemaining = CompletableDeferred<Unit>()
        val operationIds = ArrayDeque(
            listOf(
                KastMutationOperationId("00000000-0000-0000-0000-000000000001"),
                KastMutationOperationId("00000000-0000-0000-0000-000000000002"),
                KastMutationOperationId("00000000-0000-0000-0000-000000000003"),
            ),
        )
        val registry = MutationOperationRegistry(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            workspaceRoot = workspaceRoot(),
            operationIdFactory = operationIds::removeFirst,
        )

        registry.submit(
            addFileMutation("issue-420-first", "/workspace/Same.kt"),
            MutationFingerprint("first"),
        ) { reporter ->
            firstMayResolve.await()
            reporter.awaitPathAdmission(listOf("/workspace/Same.kt"))
            firstAdmitted.complete(Unit)
            releaseFirst.await()
            MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
        }
        registry.submit(
            addFileMutation("issue-420-second", "/workspace/Same.kt"),
            MutationFingerprint("second"),
        ) { reporter ->
            reporter.awaitPathAdmission(listOf("/workspace/Same.kt"))
            secondAdmitted.complete(Unit)
            releaseRemaining.await()
            MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
        }
        registry.submit(
            addFileMutation("issue-420-disjoint", "/workspace/Other.kt"),
            MutationFingerprint("disjoint"),
        ) { reporter ->
            reporter.awaitPathAdmission(listOf("/workspace/Other.kt"))
            disjointAdmitted.complete(Unit)
            releaseRemaining.await()
            MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
        }

        assertEquals(null, withTimeoutOrNull(100) { disjointAdmitted.await() })
        firstMayResolve.complete(Unit)
        firstAdmitted.await()
        disjointAdmitted.await()
        assertFalse(secondAdmitted.isCompleted)

        releaseFirst.complete(Unit)
        secondAdmitted.await()
        releaseRemaining.complete(Unit)
    }

    @Test
    fun `multi-path admission is atomic and cannot be bypassed on one requested path`() = runBlocking {
        val firstAdmitted = CompletableDeferred<Unit>()
        val multiPathAdmitted = CompletableDeferred<Unit>()
        val laterAdmitted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseMultiPath = CompletableDeferred<Unit>()
        val operationIds = ArrayDeque(
            listOf(
                KastMutationOperationId("00000000-0000-0000-0000-000000000001"),
                KastMutationOperationId("00000000-0000-0000-0000-000000000002"),
                KastMutationOperationId("00000000-0000-0000-0000-000000000003"),
            ),
        )
        val registry = MutationOperationRegistry(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            workspaceRoot = workspaceRoot(),
            operationIdFactory = operationIds::removeFirst,
        )
        registry.submit(
            addFileMutation("issue-420-held-b", "/workspace/B.kt"),
            MutationFingerprint("held-b"),
        ) { reporter ->
            reporter.awaitPathAdmission(listOf("/workspace/B.kt"))
            firstAdmitted.complete(Unit)
            releaseFirst.await()
            MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
        }
        firstAdmitted.await()
        registry.submit(
            addFileMutation("issue-420-multi", "/workspace/A.kt"),
            MutationFingerprint("multi"),
        ) { reporter ->
            reporter.awaitPathAdmission(listOf("/workspace/A.kt", "/workspace/B.kt"))
            multiPathAdmitted.complete(Unit)
            releaseMultiPath.await()
            MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
        }
        registry.submit(
            addFileMutation("issue-420-later-a", "/workspace/A.kt"),
            MutationFingerprint("later-a"),
        ) { reporter ->
            reporter.awaitPathAdmission(listOf("/workspace/A.kt"))
            laterAdmitted.complete(Unit)
            MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
        }

        assertEquals(null, withTimeoutOrNull(100) { multiPathAdmitted.await() })
        assertEquals(null, withTimeoutOrNull(100) { laterAdmitted.await() })
        releaseFirst.complete(Unit)
        multiPathAdmitted.await()
        assertFalse(laterAdmitted.isCompleted)
        releaseMultiPath.complete(Unit)
        laterAdmitted.await()
    }

    @Test
    fun `finish barrier drains earlier work and rejects new keys without reserving them`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val operationIds = ArrayDeque(
            listOf(
                KastMutationOperationId("00000000-0000-0000-0000-000000000001"),
                KastMutationOperationId("00000000-0000-0000-0000-000000000002"),
            ),
        )
        val registry = MutationOperationRegistry(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            workspaceRoot = workspaceRoot(),
            operationIdFactory = operationIds::removeFirst,
        )
        registry.submit(
            addFileMutation("issue-420-before-finish", "/workspace/Before.kt"),
            MutationFingerprint("before-finish"),
        ) {
            firstStarted.complete(Unit)
            releaseFirst.await()
            MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
        }
        firstStarted.await()
        val request = MutationFinishBarrierRequest(
            workspaceTaskId = workspaceTaskId,
            coordinationToken = MutationFinishCoordinationToken("00000000-0000-0000-0000-000000000421"),
        )
        val barrier = async(start = CoroutineStart.UNDISPATCHED) {
            registry.acquireFinishBarrier(request)
        }
        val duplicate = async(start = CoroutineStart.UNDISPATCHED) {
            registry.acquireFinishBarrier(request)
        }
        val rejectedMutation = addFileMutation("issue-420-during-finish", "/workspace/During.kt")

        val rejected = assertThrows<AnalysisException> {
            registry.submit(rejectedMutation, MutationFingerprint("during-finish")) {
                MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
            }
        }
        assertEquals("TASK_FINISH_IN_PROGRESS", rejected.errorCode)
        assertTrue(rejected.retryable)

        releaseFirst.complete(Unit)
        assertEquals(MutationFinishBarrierState.DRAINED, barrier.await().state)
        assertEquals(MutationFinishBarrierState.DRAINED, duplicate.await().state)
        assertEquals(MutationFinishBarrierState.REOPENED, registry.reopenAfterFinish(request).state)
        val admitted = registry.submit(rejectedMutation, MutationFingerprint("during-finish")) {
            MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
        }
        assertFalse(admitted.deduplicated)
    }

    @Test
    fun `repair releases only the matching interrupted finish barrier and is idempotent`() = runBlocking {
        val operationStarted = CompletableDeferred<Unit>()
        val releaseOperation = CompletableDeferred<Unit>()
        val registry = registry()
        registry.submit(
            addFileMutation("issue-420-repair", "/workspace/Repair.kt"),
            MutationFingerprint("repair"),
        ) {
            operationStarted.complete(Unit)
            releaseOperation.await()
            MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
        }
        operationStarted.await()
        val request = MutationFinishBarrierRequest(
            workspaceTaskId = workspaceTaskId,
            coordinationToken = MutationFinishCoordinationToken("00000000-0000-0000-0000-000000000422"),
        )
        val barrier = async(start = CoroutineStart.UNDISPATCHED) {
            registry.acquireFinishBarrier(request)
        }
        val wrongToken = request.copy(
            coordinationToken = MutationFinishCoordinationToken("00000000-0000-0000-0000-000000000423"),
        )

        assertThrows<AnalysisException> {
            registry.repairAfterInterruptedFinish(wrongToken)
        }
        assertEquals(MutationFinishBarrierState.REOPENED, registry.repairAfterInterruptedFinish(request).state)
        assertEquals(MutationFinishBarrierState.ABSENT, registry.repairAfterInterruptedFinish(request).state)
        assertEquals(MutationFinishBarrierState.DRAINED, barrier.await().state)
        releaseOperation.complete(Unit)
    }

    @Test
    fun `repair can reopen the exact token after backend completion won the persistence race`() = runBlocking {
        val registry = registry()
        val request = MutationFinishBarrierRequest(
            workspaceTaskId = workspaceTaskId,
            coordinationToken = MutationFinishCoordinationToken("00000000-0000-0000-0000-000000000426"),
        )
        assertEquals(MutationFinishBarrierState.DRAINED, registry.acquireFinishBarrier(request).state)
        assertEquals(MutationFinishBarrierState.COMPLETE, registry.completeAfterFinish(request).state)
        val mutation = addFileMutation("issue-420-after-complete-race", "/workspace/After.kt")
        assertThrows<AnalysisException> {
            registry.submit(mutation, MutationFingerprint("after-complete-race")) {
                MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
            }
        }
        assertThrows<AnalysisException> {
            registry.repairAfterInterruptedFinish(
                request.copy(
                    coordinationToken = MutationFinishCoordinationToken("00000000-0000-0000-0000-000000000427"),
                ),
            )
        }

        assertEquals(MutationFinishBarrierState.REOPENED, registry.repairAfterInterruptedFinish(request).state)
        val admitted = registry.submit(mutation, MutationFingerprint("after-complete-race")) {
            MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
        }
        assertFalse(admitted.deduplicated)
    }

    @Test
    fun `cancellation becomes terminal only after worker stops and is idempotent`() = runBlocking {
        val enteredIdentity = CompletableDeferred<Unit>()
        val workerStopped = CompletableDeferred<Unit>()
        val registry = registry()
        val mutation = addFileMutation("issue-333-cancel-before-edit", "/workspace/Added.kt")
        val receipt = registry.submit(mutation, MutationFingerprint("cancel-before-edit")) { reporter ->
            reporter.report(MutationProgressEvent.StageEntered(KastMutationProgressStage.IDENTITY_RESOLUTION))
            enteredIdentity.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                workerStopped.complete(Unit)
            }
        }
        enteredIdentity.await()
        val selector = KastMutationOperationSelector.ByOperationId(receipt.operation.operationId)

        val firstCancel = registry.cancel(selector)
        val secondCancel = registry.cancel(selector)

        assertTrue(firstCancel.state.cancellationRequested)
        assertTrue(secondCancel.state.cancellationRequested)
        workerStopped.await()
        val terminal = awaitTerminal(registry, selector)
        val cancelled = terminal.state as KastMutationOperationState.Cancelled
        assertEquals(KastMutationEditApplicationState.NOT_STARTED, cancelled.trace.editApplicationState)
        assertTrue(terminal.safeForFilesystemFallback)
        assertEquals(terminal, registry.cancel(selector))
        assertEquals(terminal, registry.status(selector))
    }

    @Test
    fun `cancellation after edit application retains completed edit fact`() = runBlocking {
        val editCompleted = CompletableDeferred<Unit>()
        val registry = registry()
        val mutation = addFileMutation("issue-333-cancel-after-edit", "/workspace/Added.kt")
        val receipt = registry.submit(mutation, MutationFingerprint("cancel-after-edit")) { reporter ->
            reporter.report(MutationProgressEvent.StageEntered(KastMutationProgressStage.EDIT_APPLICATION))
            reporter.report(MutationProgressEvent.EditApplicationCompleted)
            editCompleted.complete(Unit)
            awaitCancellation()
        }
        editCompleted.await()
        val selector = KastMutationOperationSelector.ByOperationId(receipt.operation.operationId)

        registry.cancel(selector)

        val terminal = awaitTerminal(registry, selector)
        val cancelled = terminal.state as KastMutationOperationState.Cancelled
        assertEquals(KastMutationEditApplicationState.COMPLETED, cancelled.trace.editApplicationState)
        assertFalse(terminal.safeForFilesystemFallback)
        assertNotEquals(KastMutationOperationState.Queued(), cancelled)
    }

    @Test
    fun `same-key cancellation cannot race worker attachment and start execution`() = runBlocking {
        val blockingDispatcher = BlockingLaunchDispatcher()
        val executionCount = AtomicInteger()
        val submission = CompletableDeferred<Unit>()
        val registry = MutationOperationRegistry(
            scope = CoroutineScope(SupervisorJob() + blockingDispatcher),
            workspaceRoot = workspaceRoot(),
            operationIdFactory = { firstOperationId },
        )
        val mutation = addFileMutation("issue-333-attachment-race", "/workspace/Race.kt")
        val fingerprint = MutationFingerprint("attachment-race")
        val submitter = thread(name = "mutation-submit-race") {
            registry.submit(mutation, fingerprint) {
                executionCount.incrementAndGet()
                MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess())
            }
            submission.complete(Unit)
        }
        assertTrue(blockingDispatcher.awaitDispatch(), "worker launch never reached dispatcher")

        val retry = registry.submit(mutation, fingerprint) {
            error("same-key retry must not install another worker")
        }
        val cancelled = registry.cancel(
            KastMutationOperationSelector.ByOperationId(retry.operation.operationId),
        )
        blockingDispatcher.release()
        submission.await()
        submitter.join()

        val terminal = awaitTerminal(
            registry,
            KastMutationOperationSelector.ByOperationId(firstOperationId),
        )
        assertTrue(cancelled.state.cancellationRequested)
        assertTrue(terminal.state is KastMutationOperationState.Cancelled)
        assertEquals(0, executionCount.get())
    }

    @Test
    fun `close cancels and joins workers while retaining truthful terminal trace`() = runBlocking {
        val editStarted = CompletableDeferred<Unit>()
        val workerStopped = CompletableDeferred<Unit>()
        val registry = registry()
        val receipt = registry.submit(
            addFileMutation("issue-333-close", "/workspace/Close.kt"),
            MutationFingerprint("close"),
        ) { reporter ->
            reporter.report(MutationProgressEvent.StageEntered(KastMutationProgressStage.EDIT_APPLICATION))
            editStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                workerStopped.complete(Unit)
            }
        }
        editStarted.await()

        registry.close()

        assertTrue(workerStopped.isCompleted)
        val terminal = registry.status(
            KastMutationOperationSelector.ByOperationId(receipt.operation.operationId),
        )
        val cancelled = terminal.state as KastMutationOperationState.Cancelled
        assertEquals(KastMutationEditApplicationState.STARTED, cancelled.trace.editApplicationState)
        assertTrue(cancelled.cancellationRequested)
        assertThrows<ConflictException> {
            registry.submit(
                addFileMutation("issue-333-after-close", "/workspace/AfterClose.kt"),
                MutationFingerprint("after-close"),
            ) { MutationOperationRegistry.ExecutionOutcome.Succeeded(scopeSuccess()) }
        }
    }

    private fun registry(): MutationOperationRegistry = MutationOperationRegistry(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        workspaceRoot = workspaceRoot(),
        operationIdFactory = { firstOperationId },
    )

    private fun workspaceRoot(): suspend () -> NormalizedPath = {
        NormalizedPath.of(Path.of("/workspace"))
    }

    private fun addFileMutation(key: String, filePath: String): KastSemanticMutation.AddFile =
        KastSemanticMutation.AddFile(
            workspaceTaskId = workspaceTaskId,
            idempotencyKey = KastMutationIdempotencyKey(key),
            request = KastAddFileRequest(filePath = filePath, contentFile = "/tmp/content.kt"),
        )

    private fun scopeSuccess(): KastSemanticMutationResult.Scope = KastSemanticMutationResult.Scope(
        KastScopeMutationSuccessResponse(
            ok = true,
            operation = KastScopeMutationOperation.ADD_FILE,
            applied = true,
            affectedFiles = listOf("/workspace/Added.kt"),
            createdFiles = listOf("/workspace/Added.kt"),
            editCount = 1,
            importChanges = 0,
            diagnostics = KastDiagnosticsSummary.from(
                DiagnosticsResult.of(
                    diagnostics = emptyList(),
                    fileStatuses = listOf(
                        FileAnalysisStatus.analyzed(
                            NormalizedPath.ofAbsolute(Path.of("/workspace/Added.kt")),
                        ),
                    ),
                    fileHashes = listOf(
                        FileHash("/workspace/Added.kt", "0".repeat(64)),
                    ),
                ),
            ),
            logFile = "",
        ),
    )

    private suspend fun awaitTerminal(
        registry: MutationOperationRegistry,
        selector: KastMutationOperationSelector,
    ) = buildList {
        repeat(200) {
            val snapshot = registry.status(selector)
            if (
                snapshot.state is KastMutationOperationState.Completed ||
                snapshot.state is KastMutationOperationState.Failed ||
                snapshot.state is KastMutationOperationState.Cancelled
            ) {
                add(snapshot)
                return@buildList
            }
            delay(5)
        }
    }.single()
}

private class BlockingLaunchDispatcher : CoroutineDispatcher() {
    private val dispatchEntered = CountDownLatch(1)
    private val dispatchRelease = CountDownLatch(1)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchEntered.countDown()
        check(dispatchRelease.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release worker dispatch" }
        Dispatchers.Default.dispatch(context, block)
    }

    fun awaitDispatch(): Boolean = dispatchEntered.await(5, TimeUnit.SECONDS)

    fun release() {
        dispatchRelease.countDown()
    }
}
