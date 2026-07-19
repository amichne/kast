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
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.FileAnalysisStatus
import io.github.amichne.kast.api.contract.skill.KastAddFileRequest
import io.github.amichne.kast.api.contract.skill.KastDiagnosticsSummary
import io.github.amichne.kast.api.contract.skill.KastScopeMutationOperation
import io.github.amichne.kast.api.contract.skill.KastScopeMutationSuccessResponse
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.validation.FileHashing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

class MutationOperationRegistryTest {
    private val firstOperationId = KastMutationOperationId("00000000-0000-0000-0000-000000000001")

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
        operationIdFactory = { firstOperationId },
    )

    private fun addFileMutation(key: String, filePath: String): KastSemanticMutation.AddFile =
        KastSemanticMutation.AddFile(
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
                        FileHash("/workspace/Added.kt", FileHashing.sha256("added")),
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
